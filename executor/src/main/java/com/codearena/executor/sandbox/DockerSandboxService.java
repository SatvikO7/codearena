package com.codearena.executor.sandbox;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionOutcome;
import com.codearena.shared.execution.ExecutionResult;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs untrusted code in disposable Docker containers.
 *
 * <p>Every security decision lives in {@link SandboxPolicy}; this class is the mechanism
 * that applies it and collects what happened. The split matters: reviewing the boundary
 * should not mean reading process plumbing.
 *
 * <h2>How source and artefacts move</h2>
 * A per-submission Docker <em>volume</em> holds the source and any compiled binary. Host
 * bind mounts are deliberately not used: this service is itself a container, so a path it
 * writes to is not the path the daemon would resolve, and making them agree means mounting
 * host directories in — more coupling and more blast radius. A named volume has neither
 * problem, is scoped to one submission, and is deleted with it.
 *
 * <p>The source reaches the volume through {@code docker cp} reading a tar stream from
 * stdin, never through a command line or a shell redirect, so its contents can never be
 * interpreted as anything but bytes in a file.
 *
 * <h2>Why containers are not created with {@code --rm}</h2>
 * Exit code 137 means "killed by SIGKILL", which for a memory-capped container is usually
 * the OOM killer but is also what a timeout kill looks like. Telling them apart requires
 * asking the daemon, and {@code --rm} races the answer away. So containers are removed
 * explicitly in a {@code finally}, and {@link SandboxReaper} is the backstop for the case
 * where this process dies between the two.
 *
 * <h2>What this is not</h2>
 * This is a hardened sandbox, not a virtual machine. Containers share the host kernel, so a
 * kernel exploit still escapes. See docs/threat-model.md, which says so in more detail
 * rather than implying otherwise.
 */
@Component
public class DockerSandboxService implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxService.class);

    /** How long a docker CLI call itself may take before we conclude the daemon is wedged. */
    private static final long DAEMON_CALL_TIMEOUT_SECONDS = 60;

    /** 128 + SIGKILL(9). Ambiguous on its own, which is why {@code OOMKilled} is consulted. */
    private static final int EXIT_SIGKILL = 137;


    /** 128 + SIGXFSZ(25): the kernel refused a write past {@code RLIMIT_FSIZE}. */
    private static final int EXIT_FILE_TOO_LARGE = 153;

    private final SandboxPolicy policy;
    private final SandboxMetrics metrics;
    private final String dockerBinary;

    public DockerSandboxService(SandboxPolicy policy,
                                SandboxMetrics metrics,
                                @Value("${codearena.sandbox.docker-binary:docker}") String dockerBinary) {
        this.policy = policy;
        this.metrics = metrics;
        this.dockerBinary = dockerBinary;
    }

    @Override
    public Workspace prepare(String submissionId, Language language, String source) {
        long preparingSince = System.nanoTime();
        LanguageSpec spec = LanguageSpec.forLanguage(language);
        // The volume name is generated here, never derived from a caller's input.
        String volume = "codearena-ws-" + UUID.randomUUID();
        DockerWorkspace workspace = new DockerWorkspace(submissionId, spec, volume);
        try {
            workspace.create(source);
            // Pure overhead: the gap between deciding to run a program and being able to.
            // Worth its own timer because a whole judgement's duration cannot say whether
            // it grew because programs got slower or because starting them did.
            metrics.recordStartup(java.time.Duration.ofNanos(System.nanoTime() - preparingSince));
            return workspace;
        } catch (RuntimeException e) {
            // A half-built workspace still owns a volume; do not leak it.
            metrics.sandboxCreationFailed();
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
         * <p>The ownership step is not cosmetic. A fresh volume is root-owned and mode 755,
         * so a compiler running as {@code nobody} cannot write its output there and every
         * compiled language fails with a confusing "no such file" from deep inside the
         * toolchain. The alternative — running the compiler as root — would hand root in the
         * container to a process whose entire input is untrusted, which is the opposite of
         * what this class is for.
         *
         * <p>The {@code chown} runs as root, but it runs <em>our</em> fixed argv against a
         * fixed path, in a container with no network and no user code in it, before any
         * user code exists in the workspace.
         */
        private void create(String source) {
            runDaemonCommand(List.of(dockerBinary, "volume", "create",
                    "--label", SandboxPolicy.OWNER_LABEL + "=true", volume));

            String seedContainer = "codearena-seed-" + UUID.randomUUID();
            try {
                runDaemonCommand(List.of(dockerBinary, "create",
                        "--name", seedContainer,
                        "--label", SandboxPolicy.OWNER_LABEL + "=true",
                        "--pull", "never",
                        "--network", "none",
                        "-v", volume + ":" + LanguageSpec.WORKDIR,
                        "--entrypoint", "chown",
                        spec.image(),
                        "-R", SandboxPolicy.SANDBOX_USER, LanguageSpec.WORKDIR));

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
         * <p>Every argument in {@code argv} comes from {@link LanguageSpec}; every isolation
         * flag comes from {@link SandboxPolicy}. No element of this list originates in a
         * request, which is why there is no shell and no quoting to get wrong.
         */
        private ExecutionResult runInContainer(List<String> argv, String stdin,
                                               ExecutionLimits limits, boolean readOnlyWorkspace) {
            String container = "codearena-run-" + UUID.randomUUID();
            List<String> command = new ArrayList<>(List.of(dockerBinary, "run", "--name", container, "-i"));
            command.addAll(policy.containerArguments(limits, volume, readOnlyWorkspace, spec.path()));
            command.addAll(List.of("--entrypoint", argv.getFirst(), spec.image()));
            command.addAll(argv.subList(1, argv.size()));

            try {
                return execute(command, stdin, limits, container);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                metrics.dockerError();
                return ExecutionResult.infrastructureFailure(
                        "Container execution failed: " + e.getClass().getSimpleName());
            } finally {
                // Unconditional. Every verdict, every exception, every interruption leaves
                // through here, which is what makes "no orphaned containers" a property of
                // the code rather than of the happy path.
                forceRemoveQuietly(container);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                runDaemonCommand(List.of(dockerBinary, "volume", "rm", "--force", volume));
            } catch (RuntimeException e) {
                // Cleanup must never mask a verdict, so this is logged rather than thrown.
                // The reaper will collect the volume on its next sweep.
                metrics.cleanupFailed();
                log.warn("event=WORKSPACE_CLEANUP_FAILED submission={} volume={} reason={}",
                        submissionId, volume, e.toString());
            }
        }

        private void copyIntoContainer(String container, String fileName, String content) {
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
                    throw new SandboxInfrastructureException("Timed out copying source into the sandbox");
                }
                if (process.exitValue() != 0) {
                    throw new SandboxInfrastructureException("Could not stage source: " + output);
                }
            } catch (IOException e) {
                throw new SandboxInfrastructureException("Could not stage source: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxInfrastructureException("Interrupted while staging source");
            }
        }
    }

    // ---------------------------------------------------------------------- plumbing

    /**
     * Runs the container and collects its output under both a time and a size ceiling.
     *
     * <p>stdout and stderr are drained on separate threads. That is not tidiness: a
     * container whose output fills the pipe buffer blocks forever if nobody is reading, and
     * the program would hang rather than be judged.
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
        // Timed here rather than per outcome: every path below returns after this point, so
        // one call covers the clean exits, the timeouts and the kills alike. A timer that
        // only recorded successful runs would report the sandbox as fastest precisely when
        // it is spending all its time killing things.
        metrics.recordExecution(java.time.Duration.ofMillis(durationMs));

        if (!exited) {
            // The wall-clock budget is the outer bound. Kill the container itself, not just
            // the CLI client: killing the client would leave the workload running. Removing
            // the container kills every process in its cgroup, so a child that outlived its
            // parent dies with it.
            metrics.timedOut();
            forceRemoveQuietly(containerName);
            process.destroyForcibly();
            return ExecutionResult.killed(ExecutionOutcome.TIMED_OUT,
                    stdout.join().text(), stderr.join().text(), durationMs);
        }

        BoundedOutput out = stdout.join();
        BoundedOutput err = stderr.join();

        if (out.truncated() || err.truncated()) {
            metrics.outputLimitExceeded();
            forceRemoveQuietly(containerName);
            return ExecutionResult.killed(ExecutionOutcome.OUTPUT_LIMIT_EXCEEDED,
                    out.text(), err.text(), durationMs);
        }

        int exitCode = process.exitValue();

        if (exitCode == EXIT_SIGKILL) {
            // Ask rather than assume. A SIGKILL here is usually the OOM killer, but it is
            // also what a concurrent teardown looks like, and reporting MEMORY_LIMIT_EXCEEDED
            // for an infrastructure kill would blame the submitter for our own behaviour.
            if (wasOomKilled(containerName)) {
                metrics.outOfMemory();
                return ExecutionResult.killed(ExecutionOutcome.OUT_OF_MEMORY,
                        out.text(), err.text(), durationMs);
            }
        }
        if (exitCode == EXIT_FILE_TOO_LARGE) {
            metrics.fileLimitExceeded();
            return ExecutionResult.killed(ExecutionOutcome.FILE_LIMIT_EXCEEDED,
                    out.text(), err.text(), durationMs);
        }
        return ExecutionResult.completed(exitCode, out.text(), err.text(), durationMs);
    }

    /**
     * Asks the daemon whether the kernel's OOM killer ended this container.
     *
     * <p>A best-effort question: if the container has already gone, or the daemon will not
     * answer, the caller falls back to reporting a plain non-zero exit. Guessing "out of
     * memory" from an unavailable answer would be worse than reporting what is certain.
     */
    private boolean wasOomKilled(String container) {
        try {
            Process process = new ProcessBuilder(List.of(
                    dockerBinary, "inspect", "--format", "{{.State.OOMKilled}}", container))
                    .redirectErrorStream(true)
                    .start();
            String output = readAll(process.getInputStream(), 256).strip();
            if (!process.waitFor(DAEMON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && "true".equalsIgnoreCase(output);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void runDaemonCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readAll(process.getInputStream(), 8192);
            if (!process.waitFor(DAEMON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SandboxInfrastructureException("Docker command timed out");
            }
            if (process.exitValue() != 0) {
                throw new SandboxInfrastructureException("Docker command failed: " + output.strip());
            }
        } catch (IOException e) {
            throw new SandboxInfrastructureException("Docker is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxInfrastructureException("Interrupted waiting for Docker");
        }
    }

    /** Best-effort teardown; a failure here must never change a verdict. */
    private void forceRemoveQuietly(String container) {
        try {
            Process process = new ProcessBuilder(List.of(dockerBinary, "rm", "--force", "--volumes", container))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                metrics.cleanupFailed();
                log.warn("event=CONTAINER_CLEANUP_TIMEOUT container={}", container);
            }
        } catch (IOException e) {
            metrics.cleanupFailed();
            log.warn("event=CONTAINER_CLEANUP_FAILED container={} reason={}", container, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeContainerQuietly(String container) {
        forceRemoveQuietly(container);
    }

    private static String readAll(InputStream stream, int limit) {
        return readBounded(stream, limit).text();
    }

    /**
     * Reads a stream, stopping once the limit is passed.
     *
     * <p>Stopping is the point. A program printing an endless stream would otherwise fill
     * this process's heap long before any timeout fired.
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
}
