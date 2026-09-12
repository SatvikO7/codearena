package com.codearena.worker.execution;

import com.codearena.shared.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs untrusted code in disposable Docker containers.
 *
 * <h2>How isolation is achieved</h2>
 * Every run is a fresh container created with, and only with, arguments this class
 * constructs:
 * <ul>
 *   <li>{@code --network none} — no interface but loopback. The program cannot reach
 *       PostgreSQL, Redis, the internet, or anything else on the host.</li>
 *   <li>{@code --memory} with {@code --memory-swap} set equal to it — no swap, so the
 *       limit is real rather than something the kernel pages around.</li>
 *   <li>{@code --cpus} — a fractional quota, so a busy loop cannot starve the host.</li>
 *   <li>{@code --pids-limit} — the defence against fork bombs, which no timeout catches
 *       because the shell never returns.</li>
 *   <li>{@code --cap-drop ALL} and {@code --security-opt no-new-privileges} — no
 *       capabilities, and no way to regain any through setuid.</li>
 *   <li>{@code --read-only} with a small {@code tmpfs} for the working directory — nothing
 *       the program writes survives, and it cannot fill a disk.</li>
 *   <li>{@code --user 65534:65534} — runs as nobody, never root, even inside the container.</li>
 *   <li>No volume from the host, and emphatically no Docker socket. The program has no
 *       path to the daemon that is running it.</li>
 * </ul>
 *
 * <h2>How source and artefacts move</h2>
 * A per-submission Docker <em>volume</em> holds the source and any compiled binary. Host
 * bind mounts are deliberately not used: the worker is itself a container, so a path it
 * writes to is not the path the daemon would resolve, and making them agree means mounting
 * host directories into the worker — more coupling and more blast radius. A named volume
 * has neither problem, is scoped to one submission, and is deleted with it.
 *
 * <h2>What this is not</h2>
 * This is a competent sandbox, not a hardened one. Containers share the host kernel, so a
 * kernel exploit escapes. The worker holds the Docker socket and is therefore
 * host-equivalent if the worker itself is compromised. Both are documented in
 * docs/security.md with the hardening that Phase 12 should bring.
 */
@Component
public class DockerExecutionService implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionService.class);

    /** How long a docker CLI call itself may take before we conclude the daemon is wedged. */
    private static final long DAEMON_CALL_TIMEOUT_SECONDS = 60;

    /**
     * The uid:gid every sandbox runs as — {@code nobody}, which exists in all three language
     * images and owns nothing. Never root, even inside a container that is already isolated:
     * defence in depth costs nothing here and a container escape starting from uid 0 is
     * considerably more useful to an attacker than one starting from 65534.
     */
    private static final String SANDBOX_USER = "65534:65534";

    private final String dockerBinary;
    private final int workspaceTmpfsMb;

    public DockerExecutionService(
            @Value("${codearena.execution.docker-binary:docker}") String dockerBinary,
            @Value("${codearena.execution.workspace-tmpfs-mb:64}") int workspaceTmpfsMb) {
        this.dockerBinary = dockerBinary;
        this.workspaceTmpfsMb = workspaceTmpfsMb;
    }

    @Override
    public Workspace prepare(String submissionId, Language language, String source) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);
        // The volume name is generated here, never derived from user input.
        String volume = "codearena-ws-" + UUID.randomUUID();
        DockerWorkspace workspace = new DockerWorkspace(submissionId, spec, volume);
        try {
            workspace.create(source);
            return workspace;
        } catch (RuntimeException e) {
            // A half-built workspace still owns a volume; do not leak it.
            workspace.close();
            throw e;
        }
    }

    // --------------------------------------------------------------------- workspace

    private final class DockerWorkspace implements Workspace {

        private final String submissionId;
        private final LanguageSpec spec;
        private final String volume;
        private boolean closed;

        private DockerWorkspace(String submissionId, LanguageSpec spec, String volume) {
            this.submissionId = submissionId;
            this.spec = spec;
            this.volume = volume;
        }

        /**
         * Creates the volume, seeds it with the source, and hands it to the sandbox user.
         *
         * <p>The source reaches the container through {@code docker cp} on a stopped
         * container, not through a command line or a shell redirect, so its contents can
         * never be interpreted as anything but bytes in a file.
         *
         * <p>The ownership step is not cosmetic. A fresh volume is root-owned and mode 755,
         * so a compiler running as {@code nobody} cannot write its output there and every
         * compiled language fails with a confusing "no such file" from deep inside the
         * toolchain. The alternative — running the compiler as root — would hand root in the
         * container to a process whose entire input is untrusted, which is the opposite of
         * what this class is for.
         *
         * <p>The {@code chown} runs as root, but it runs <em>our</em> fixed argv against a
         * fixed path, before any user code exists in the workspace.
         */
        private void create(String source) {
            runDaemonCommand(List.of(dockerBinary, "volume", "create", volume));

            // A short-lived helper container: it gives `docker cp` a destination that maps
            // onto the volume, and then fixes the ownership. It never runs the user's code.
            String seedContainer = "codearena-seed-" + UUID.randomUUID();
            try {
                runDaemonCommand(List.of(dockerBinary, "create",
                        "--name", seedContainer,
                        "--network", "none",
                        "-v", volume + ":" + LanguageSpec.WORKDIR,
                        "--entrypoint", "chown",
                        spec.image(),
                        "-R", SANDBOX_USER, LanguageSpec.WORKDIR));

                copyIntoContainer(seedContainer, spec.sourceFileName(), source);

                runDaemonCommand(List.of(dockerBinary, "start", "--attach", seedContainer));
            } finally {
                removeContainerQuietly(seedContainer);
            }
        }

        @Override
        public ExecutionResult compile(ExecutionLimits limits) {
            if (!spec.requiresCompilation()) {
                return ExecutionResult.completed(0, "", "", 0);
            }
            // Compilation writes the artefact back to the volume, so the mount is writable
            // here and read-only for every subsequent run.
            return runInContainer(spec.compileCommand(), null, limits, false);
        }

        @Override
        public ExecutionResult run(String stdin, ExecutionLimits limits) {
            return runInContainer(spec.runCommand(), stdin, limits, true);
        }

        /**
         * The single place a container carrying user code is created and started.
         *
         * <p>Every argument in {@code argv} comes from {@link LanguageSpec}; every flag
         * comes from {@link ExecutionLimits}. No element of this list originates in a
         * request, which is why there is no shell and no quoting to get wrong.
         */
        private ExecutionResult runInContainer(List<String> argv, String stdin,
                                               ExecutionLimits limits, boolean readOnlyWorkspace) {
            String container = "codearena-run-" + UUID.randomUUID();
            List<String> command = new ArrayList<>(List.of(
                    dockerBinary, "run",
                    "--name", container,
                    "--rm",
                    "-i",

                    // --- isolation ---
                    "--network", "none",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges",
                    "--user", SANDBOX_USER,

                    // --- resource ceilings, enforced by the kernel ---
                    "--memory", limits.memoryMb() + "m",
                    "--memory-swap", limits.memoryMb() + "m",
                    "--cpus", formatCpus(limits.cpus()),
                    "--pids-limit", String.valueOf(limits.pids()),

                    // --- filesystem ---
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=" + workspaceTmpfsMb + "m",
                    "-v", volume + ":" + LanguageSpec.WORKDIR + (readOnlyWorkspace ? ":ro" : ""),
                    "-w", LanguageSpec.WORKDIR,

                    // --- environment ---
                    // The sandbox inherits nothing. Without this the container would carry
                    // the image's own variables, and any future worker secret alongside them.
                    "--env", "HOME=/tmp",
                    "--entrypoint", argv.getFirst(),
                    spec.image()));
            command.addAll(argv.subList(1, argv.size()));

            try {
                return execute(command, stdin, limits, container);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                forceKillQuietly(container);
                return ExecutionResult.infrastructureFailure("Container execution failed: " + e.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Volume removal can fail if a container still holds it; --force is not
            // available for volumes, so this is best-effort and logged rather than thrown,
            // because cleanup must never mask the verdict.
            try {
                runDaemonCommand(List.of(dockerBinary, "volume", "rm", "--force", volume));
            } catch (RuntimeException e) {
                log.warn("event=WORKSPACE_CLEANUP_FAILED submission={} volume={} reason={}",
                        submissionId, volume, e.toString());
            }
        }

        private void copyIntoContainer(String container, String fileName, String content) {
            // `docker cp -` reads a tar stream from stdin, which keeps the file's contents
            // entirely off the command line.
            List<String> command = List.of(dockerBinary, "cp", "-",
                    container + ":" + LanguageSpec.WORKDIR);
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try (OutputStream out = process.getOutputStream()) {
                    TarWriter.writeSingleFile(out, fileName, content.getBytes(StandardCharsets.UTF_8));
                }
                String output = readAll(process.getInputStream(), 8192);
                if (!process.waitFor(DAEMON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ExecutionInfrastructureException("Timed out copying source into the sandbox");
                }
                if (process.exitValue() != 0) {
                    throw new ExecutionInfrastructureException("Could not stage source: " + output);
                }
            } catch (IOException e) {
                throw new ExecutionInfrastructureException("Could not stage source: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExecutionInfrastructureException("Interrupted while staging source");
            }
        }
    }

    // ---------------------------------------------------------------------- plumbing

    /**
     * Runs the container and collects its output under both a time and a size ceiling.
     *
     * <p>stdout and stderr are drained on separate threads. That is not tidiness: a
     * container whose output fills the pipe buffer blocks forever if nobody is reading,
     * and the program would hang rather than be judged.
     */
    private ExecutionResult execute(List<String> command, String stdin,
                                    ExecutionLimits limits, String containerName)
            throws IOException, InterruptedException {

        long startedAt = System.nanoTime();
        Process process = new ProcessBuilder(command).start();

        CompletableFuture<BoundedOutput> stdout =
                CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream(), limits.outputBytes()));
        CompletableFuture<BoundedOutput> stderr =
                CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream(), limits.outputBytes()));

        // Feed the test input, then close stdin so a program reading to EOF terminates.
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // The program exited without reading its input. Legitimate, and not an error.
            log.debug("sandbox closed stdin early: {}", e.getMessage());
        }

        boolean exited = process.waitFor(limits.wallClockMillis(), TimeUnit.MILLISECONDS);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (!exited) {
            // The wall-clock budget is the outer bound. Kill the container itself, not just
            // the CLI client: killing the client would leave the workload running.
            forceKillQuietly(containerName);
            process.destroyForcibly();
            return ExecutionResult.killed(ExecutionOutcome.TIMED_OUT,
                    stdout.join().text(), stderr.join().text(), durationMs);
        }

        BoundedOutput out = stdout.join();
        BoundedOutput err = stderr.join();

        if (out.truncated() || err.truncated()) {
            forceKillQuietly(containerName);
            return ExecutionResult.killed(ExecutionOutcome.OUTPUT_LIMIT_EXCEEDED,
                    out.text(), err.text(), durationMs);
        }

        int exitCode = process.exitValue();
        // 137 is 128 + SIGKILL. For a container with a memory ceiling that is
        // overwhelmingly the OOM killer, which is how memory exhaustion is detected without
        // a second round trip to `docker inspect` on every single run.
        if (exitCode == 137) {
            return ExecutionResult.killed(ExecutionOutcome.OUT_OF_MEMORY,
                    out.text(), err.text(), durationMs);
        }
        return ExecutionResult.completed(exitCode, out.text(), err.text(), durationMs);
    }

    /** Docker wants a plain decimal; the platform's locale must not turn it into a comma. */
    private String formatCpus(double cpus) {
        return String.format(Locale.ROOT, "%.2f", cpus);
    }

    private void runDaemonCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readAll(process.getInputStream(), 8192);
            if (!process.waitFor(DAEMON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ExecutionInfrastructureException("Docker command timed out");
            }
            if (process.exitValue() != 0) {
                throw new ExecutionInfrastructureException("Docker command failed: " + output.strip());
            }
        } catch (IOException e) {
            throw new ExecutionInfrastructureException("Docker is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionInfrastructureException("Interrupted waiting for Docker");
        }
    }

    /** Best-effort teardown; a failure here must never change a verdict. */
    private void forceKillQuietly(String container) {
        try {
            new ProcessBuilder(List.of(dockerBinary, "rm", "--force", container))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.warn("could not remove container {}: {}", container, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeContainerQuietly(String container) {
        forceKillQuietly(container);
    }

    private static String readAll(InputStream stream, int limit) {
        return readBounded(stream, limit).text();
    }

    /**
     * Reads a stream, stopping once the limit is passed.
     *
     * <p>Stopping is the point. A program printing an endless stream would otherwise fill
     * the worker's heap long before any timeout fired.
     */
    private static BoundedOutput readBounded(InputStream stream, int limit) {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        boolean truncated = false;
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (collected.size() + read > limit) {
                    collected.write(buffer, 0, Math.max(0, limit - collected.size()));
                    truncated = true;
                    break;
                }
                collected.write(buffer, 0, read);
            }
        } catch (IOException e) {
            // The container was killed mid-write. Whatever was captured still stands.
            log.debug("sandbox output stream ended early: {}", e.getMessage());
        }
        return new BoundedOutput(collected.toString(StandardCharsets.UTF_8), truncated);
    }

    private record BoundedOutput(String text, boolean truncated) {
    }

    /** The judge could not run the code. Distinct from the code running and failing. */
    public static class ExecutionInfrastructureException extends RuntimeException {
        public ExecutionInfrastructureException(String message) {
            super(message);
        }
    }
}
