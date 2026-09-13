package com.codearena.executor.sandbox;

import com.codearena.shared.execution.ExecutionLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every security decision about a sandbox container, in one place.
 *
 * <h2>Why this is its own class</h2>
 * Before Phase 6 these flags were assembled inline in the middle of the code that also
 * spawned processes, parsed output and handled failures. That is a bad place for a security
 * boundary to live: reviewing it meant reading execution logic, and testing it meant running
 * a container. Here the policy is a pure function from limits to arguments, so it can be
 * read on one screen and asserted on directly — and {@code SandboxIsolationIT} then proves
 * the flags do what the names claim against a real daemon, because a test that only checks
 * the argument list proves nothing about the kernel.
 *
 * <h2>The controls, and what each one stops</h2>
 * <ul>
 *   <li>{@code --network none} — no interface but loopback. No TCP, no UDP, no DNS, no
 *       reaching PostgreSQL, Redis, the metadata service or the internet.</li>
 *   <li>{@code --cap-drop ALL} — no capabilities at all, not even the default set Docker
 *       would otherwise grant.</li>
 *   <li>{@code --security-opt no-new-privileges} — a setuid binary cannot raise privilege,
 *       so dropping capabilities cannot be undone.</li>
 *   <li>{@code --security-opt seccomp=…} — a syscall allowlist narrower than Docker's
 *       default; see {@code sandbox/seccomp/codearena.json}. Falls back to the daemon
 *       default when the profile is unreadable, which is weaker but never open.</li>
 *   <li>{@code --user 65534:65534} — {@code nobody}. Never root, even inside a container.</li>
 *   <li>{@code --read-only} — the image filesystem cannot be modified.</li>
 *   <li>{@code --tmpfs /tmp:…,noexec,nosuid,nodev} — the only writable place at run time.
 *       {@code noexec} means a program cannot write a payload and then run it; the size cap
 *       is charged to the container's memory cgroup, so filling it is an OOM kill rather
 *       than a full host disk.</li>
 *   <li>{@code --ipc none}, {@code --cgroupns private} — no shared IPC namespace, and the
 *       container's own cgroup tree rather than the host's.</li>
 *   <li>{@code --pull never} — judging never reaches a registry. Images are built ahead of
 *       time; a missing one is an infrastructure failure, not a silent download.</li>
 *   <li>{@code --init} — PID 1 is a real reaper, so a program that forks and exits does not
 *       leave zombies behind inside the container.</li>
 *   <li>{@code --ulimit fsize} — a hard kernel ceiling on the size of any single file the
 *       program writes. This is the one genuinely enforced per-execution disk bound on a
 *       daemon without filesystem project quotas; see {@link #diskBoundNote()}.</li>
 *   <li>{@code --ulimit core=0} — no core dumps. A segfaulting C++ program would otherwise
 *       be able to write a file the size of its address space.</li>
 *   <li>{@code --ulimit nofile} — bounds file-descriptor exhaustion.</li>
 * </ul>
 *
 * <h2>What is deliberately not set</h2>
 * <ul>
 *   <li><b>{@code --ulimit nproc}</b>. RLIMIT_NPROC counts processes per <em>uid across the
 *       whole host</em>, and every sandbox runs as the same uid 65534. Setting it would let
 *       one submission's processes count against a concurrent submission's budget — two
 *       runs interfering with each other, which is precisely what the isolation is for.
 *       {@code --pids-limit} is the correct control because it is per-cgroup.</li>
 *   <li><b>{@code --storage-opt size}</b>. Accepted by this daemon and silently not
 *       enforced: a container capped at 64&nbsp;MB wrote a 100&nbsp;MB file successfully,
 *       because overlayfs here has no project quota. Configuring it would look like a disk
 *       limit while being nothing of the kind.</li>
 *   <li><b>AppArmor / SELinux.</b> Neither is present on every target platform — this
 *       daemon reports only {@code seccomp} and {@code cgroupns} — so a profile name is
 *       applied only when {@link #apparmorProfile} names one that the host actually has.</li>
 * </ul>
 */
@Component
public class SandboxPolicy {

    private static final Logger log = LoggerFactory.getLogger(SandboxPolicy.class);

    /**
     * The uid:gid every sandbox runs as — {@code nobody}, which owns nothing in any of the
     * images. A container escape that begins at uid 65534 is considerably less useful than
     * one that begins at uid 0, and the cost of insisting on it is zero.
     */
    public static final String SANDBOX_USER = "65534:65534";

    /** Marks containers and volumes as ours, so the reaper can find strays unambiguously. */
    public static final String OWNER_LABEL = "com.codearena.sandbox";

    private final int workspaceTmpfsMb;
    private final long maxFileBytes;
    private final int maxOpenFiles;
    private final String seccompProfilePath;
    private final String apparmorProfile;
    private final boolean seccompAvailable;

    public SandboxPolicy(
            @Value("${codearena.sandbox.tmpfs-mb:64}") int workspaceTmpfsMb,
            @Value("${codearena.sandbox.max-file-bytes:67108864}") long maxFileBytes,
            @Value("${codearena.sandbox.max-open-files:256}") int maxOpenFiles,
            @Value("${codearena.sandbox.seccomp-profile:/app/seccomp/codearena.json}") String seccompProfilePath,
            @Value("${codearena.sandbox.apparmor-profile:}") String apparmorProfile) {

        this.workspaceTmpfsMb = workspaceTmpfsMb;
        this.maxFileBytes = maxFileBytes;
        this.maxOpenFiles = maxOpenFiles;
        this.seccompProfilePath = seccompProfilePath;
        this.apparmorProfile = apparmorProfile;
        this.seccompAvailable = seccompProfilePath != null
                && !seccompProfilePath.isBlank()
                && Files.isReadable(Path.of(seccompProfilePath));

        if (seccompAvailable) {
            log.info("event=SANDBOX_SECCOMP_ENABLED profile={}", seccompProfilePath);
        } else {
            // Stated loudly rather than silently degraded: the sandbox still has the
            // daemon's default profile, which is not nothing, but it is not what was asked
            // for and an operator should know.
            log.warn("event=SANDBOX_SECCOMP_UNAVAILABLE profile={} effect=falling_back_to_daemon_default",
                    seccompProfilePath);
        }
    }

    public boolean seccompProfileApplied() {
        return seccompAvailable;
    }

    /**
     * The isolation and resource arguments for one sandbox container.
     *
     * <p>Pure: same inputs, same list, no side effects, nothing read from the environment.
     * Callers append the image and the fixed argv from {@link LanguageSpec}; nothing in this
     * list or in theirs originates in a request.
     *
     * @param workspaceVolume the per-submission volume holding source and artefacts
     * @param readOnlyWorkspace true for runs, false for compilation, which must write
     */
    public List<String> containerArguments(ExecutionLimits limits,
                                           String workspaceVolume,
                                           boolean readOnlyWorkspace,
                                           String path) {
        List<String> args = new ArrayList<>(List.of(
                // --- identity and lifecycle ---
                "--label", OWNER_LABEL + "=true",
                "--pull", "never",
                "--init",
                // A fixed hostname: the default is the container id, which tells a program
                // exactly what to ask the daemon about if it ever found a way to ask.
                "--hostname", "sandbox",

                // --- isolation ---
                "--network", "none",
                "--ipc", "none",
                "--cgroupns", "private",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", SANDBOX_USER,

                // --- resource ceilings, enforced by the kernel ---
                // memory-swap equal to memory means no swap: the ceiling is real rather
                // than something the kernel quietly pages around.
                "--memory", limits.memoryMb() + "m",
                "--memory-swap", limits.memoryMb() + "m",
                "--cpus", formatCpus(limits.cpus()),
                "--pids-limit", String.valueOf(limits.pids()),
                "--ulimit", "fsize=" + maxFileBytes + ":" + maxFileBytes,
                "--ulimit", "core=0:0",
                "--ulimit", "nofile=" + maxOpenFiles + ":" + maxOpenFiles,

                // --- filesystem ---
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + workspaceTmpfsMb + "m",
                "-v", workspaceVolume + ":" + LanguageSpec.WORKDIR + (readOnlyWorkspace ? ":ro" : ""),
                "-w", LanguageSpec.WORKDIR));

        if (seccompAvailable) {
            args.addAll(List.of("--security-opt", "seccomp=" + seccompProfilePath));
        }
        if (apparmorProfile != null && !apparmorProfile.isBlank()) {
            args.addAll(List.of("--security-opt", "apparmor=" + apparmorProfile));
        }
        args.addAll(environment(path));
        return args;
    }

    /**
     * The complete environment a sandbox sees.
     *
     * <p>Two jobs at once. The security job: <b>nothing from this process's environment is
     * passed through</b>. No database URL, Redis password, executor token or cloud
     * credential can reach a program that enumerates its own environment, because no code
     * path copies the parent environment in — and a test enumerates the result to prove it.
     *
     * <p>One honest qualification. {@code --env} <em>adds to</em> the image's own
     * environment rather than replacing it, so variables the base image declares still
     * appear. Those are public build constants (a GCC version, a GPG key id), never
     * secrets, and the sandbox images blank them anyway; but "the environment is exactly
     * these six variables" would be a false claim and this is the accurate one.
     *
     * <p>The determinism job: locale, timezone and PATH are pinned, so a verdict does not
     * depend on how the host happens to be configured. Two runs of the same program on two
     * machines see the same environment — and because the environment is replaced rather
     * than extended, it cannot pick up anything the image or the daemon happened to set.
     */
    private List<String> environment(String path) {
        return List.of(
                "--env", "HOME=/tmp",
                "--env", "TMPDIR=/tmp",
                "--env", "LANG=C.UTF-8",
                "--env", "LC_ALL=C.UTF-8",
                // LANGUAGE overrides LC_ALL for message translation and the JDK image sets
                // it to en_US. Pinning LANG and LC_ALL while leaving this one alone leaves
                // locale half-controlled.
                "--env", "LANGUAGE=C",
                "--env", "TZ=UTC",
                // Supplied by the language spec: the correct PATH is a property of the
                // image, and a single shared value hides the JDK's tools entirely.
                "--env", "PATH=" + path);
    }

    /** Docker wants a plain decimal; the platform's locale must not turn it into a comma. */
    private String formatCpus(double cpus) {
        return String.format(Locale.ROOT, "%.2f", cpus);
    }

    /**
     * What bounds a sandbox's disk use, stated for the documentation that quotes it.
     *
     * <p>At run time the workspace is mounted read-only and the only writable filesystem is
     * a size-capped tmpfs charged to the memory cgroup, so a running submission cannot touch
     * the host disk at all. During compilation the workspace is writable, and there the
     * bound is {@code RLIMIT_FSIZE} per file plus the compile timeout — not a quota on total
     * bytes. A filesystem with project quotas would allow a real one.
     */
    public String diskBoundNote() {
        return "run: read-only workspace + %dMB tmpfs; compile: RLIMIT_FSIZE %d bytes per file"
                .formatted(workspaceTmpfsMb, maxFileBytes);
    }
}
