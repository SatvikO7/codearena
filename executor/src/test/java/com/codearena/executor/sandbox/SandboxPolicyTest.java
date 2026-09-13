package com.codearena.executor.sandbox;

import com.codearena.shared.execution.ExecutionLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandbox argument list, asserted directly.
 *
 * <h2>What these tests are, and are not</h2>
 * These assert the <em>shape</em> of the command: that the flags are present, spelled the
 * way Docker spells them, and derived from the limits rather than from anything else. They
 * prove nothing about whether the kernel honours any of it — a string in a list is a string
 * in a list. {@code SandboxSecurityIT} does that part by running malicious programs and
 * observing what happens, and it is the test that actually establishes the security
 * properties.
 *
 * <p>Both exist because they fail for different reasons. If someone deletes
 * {@code --cap-drop ALL}, this suite says which flag went missing in milliseconds; the
 * integration suite says a program acquired capabilities, several minutes later, on a
 * machine with a working Docker daemon. The first is a better error message; only the second
 * is evidence.
 */
class SandboxPolicyTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(2_000, 256, 1.5, 64, 65_536);

    private SandboxPolicy policy() {
        // No seccomp profile on this path: the file does not exist, which is also the
        // fallback case worth covering below.
        return new SandboxPolicy(64, 67_108_864L, 256, "/nonexistent/seccomp.json", "");
    }

    private List<String> arguments() {
        return policy().containerArguments(LIMITS, "vol-1", true, "/usr/bin");
    }

    @Test
    void dropsEveryCapability() {
        assertThat(pairs(arguments())).contains("--cap-drop ALL");
    }

    @Test
    void forbidsRegainingPrivilegeThroughSetuid() {
        assertThat(pairs(arguments())).contains("--security-opt no-new-privileges");
    }

    @Test
    void runsAsNobodyRatherThanRoot() {
        assertThat(pairs(arguments())).contains("--user 65534:65534");
        assertThat(arguments()).doesNotContain("--privileged");
    }

    @Test
    void givesTheContainerNoNetworkAtAll() {
        assertThat(pairs(arguments())).contains("--network none");
    }

    @Test
    void isolatesIpcAndCgroupNamespaces() {
        assertThat(pairs(arguments())).contains("--ipc none", "--cgroupns private");
    }

    /**
     * Swap must equal memory. If it does not, the kernel pages around the limit and the
     * "memory ceiling" becomes a suggestion — the program keeps running, slowly, instead of
     * being killed, and MEMORY_LIMIT_EXCEEDED never fires.
     */
    @Test
    void setsSwapEqualToMemorySoTheLimitIsReal() {
        assertThat(pairs(arguments()))
                .contains("--memory 256m", "--memory-swap 256m");
    }

    @Test
    void carriesTheCpuAndProcessCeilingsFromTheLimits() {
        assertThat(pairs(arguments())).contains("--cpus 1.50", "--pids-limit 64");
    }

    /**
     * A comma here would be rejected by the daemon, and a machine whose default locale
     * formats decimals with one is not unusual. Only the --cpus value is checked: the tmpfs
     * specification legitimately contains commas.
     */
    @Test
    void formatsCpusWithADecimalPointRegardlessOfLocale() {
        List<String> arguments = arguments();
        String cpus = arguments.get(arguments.indexOf("--cpus") + 1);

        assertThat(cpus).isEqualTo("1.50").doesNotContain(",");
    }

    @Test
    void mountsTheRootFilesystemReadOnlyWithOnlyABoundedTmpfs() {
        List<String> arguments = arguments();
        assertThat(arguments).contains("--read-only");
        assertThat(pairs(arguments))
                .contains("--tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m");
    }

    /**
     * {@code noexec} is the one that matters most here: without it a program can write a
     * payload into the only writable directory it has and then run it.
     */
    @Test
    void makesTheWritableDirectoryNonExecutable() {
        assertThat(String.join(" ", arguments())).contains("noexec");
    }

    @Test
    void mountsTheWorkspaceReadOnlyForRunsAndWritableForCompilation() {
        assertThat(pairs(policy().containerArguments(LIMITS, "vol-1", true, "/usr/bin")))
                .contains("-v vol-1:/work:ro");
        assertThat(pairs(policy().containerArguments(LIMITS, "vol-1", false, "/usr/bin")))
                .contains("-v vol-1:/work");
    }

    @Test
    void boundsFileSizeAndOpenFilesAndForbidsCoreDumps() {
        assertThat(pairs(arguments()))
                .contains("--ulimit fsize=67108864:67108864",
                          "--ulimit core=0:0",
                          "--ulimit nofile=256:256");
    }

    /**
     * RLIMIT_NPROC counts processes per uid across the whole host, and every sandbox runs as
     * the same uid. Setting it would let one submission's processes count against a
     * concurrent submission's budget — two runs interfering, which is the opposite of what
     * the isolation is for. {@code --pids-limit} is the per-cgroup control and is set above.
     */
    @Test
    void doesNotSetNprocBecauseItIsSharedAcrossContainers() {
        assertThat(String.join(" ", arguments())).doesNotContain("nproc");
    }

    /**
     * Accepted by Docker on overlayfs and silently not enforced: a container capped at 64MB
     * wrote a 100MB file. Setting it would look like a disk limit while being none.
     */
    @Test
    void doesNotSetStorageOptBecauseItIsNotEnforcedHere() {
        assertThat(arguments()).doesNotContain("--storage-opt");
    }

    @Test
    void neverReachesARegistryWhileJudging() {
        assertThat(pairs(arguments())).contains("--pull never");
    }

    @Test
    void labelsContainersSoStraysCanBeFound() {
        assertThat(pairs(arguments())).contains("--label com.codearena.sandbox=true");
    }

    /** The default hostname is the container id, which names the thing to ask about. */
    @Test
    void replacesTheHostnameWithAFixedValue() {
        assertThat(pairs(arguments())).contains("--hostname sandbox");
    }

    @Test
    void pinsLocaleTimezoneAndPathForDeterminism() {
        assertThat(pairs(arguments()))
                .contains("--env LANG=C.UTF-8",
                          "--env LC_ALL=C.UTF-8",
                          "--env LANGUAGE=C",
                          "--env TZ=UTC",
                          "--env HOME=/tmp",
                          "--env TMPDIR=/tmp",
                          "--env PATH=/usr/bin");
    }

    /**
     * The PATH is supplied by the language spec, because the correct value is a property of
     * the image: the JDK image keeps its tools in /opt/java/openjdk/bin and the others do
     * not. A single shared PATH makes the Java compiler vanish.
     */
    @Test
    void takesThePathFromTheCaller() {
        assertThat(pairs(policy().containerArguments(LIMITS, "v", true, "/opt/java/openjdk/bin")))
                .contains("--env PATH=/opt/java/openjdk/bin");
    }

    @Test
    void omitsTheSeccompFlagWhenTheProfileCannotBeRead() {
        assertThat(policy().seccompProfileApplied()).isFalse();
        assertThat(String.join(" ", arguments())).doesNotContain("seccomp");
    }

    /**
     * A missing profile degrades to the daemon's default rather than to nothing. The default
     * is weaker than ours but is still an allowlist; falling back to no filtering at all
     * would be a silent downgrade of the sandbox.
     */
    @Test
    void appliesTheSeccompProfileWhenItIsReadable() {
        SandboxPolicy withProfile = new SandboxPolicy(
                64, 67_108_864L, 256, SandboxIsolationIT.seccompProfilePath(), "");

        assertThat(withProfile.seccompProfileApplied()).isTrue();
        assertThat(pairs(withProfile.containerArguments(LIMITS, "v", true, "/usr/bin")))
                .anyMatch(pair -> pair.startsWith("--security-opt seccomp="));
    }

    /**
     * Naming a profile the host does not have makes every container fail to start, so an
     * unset value must produce no flag at all rather than a default guess. Docker Desktop
     * reports no AppArmor; a Linux host may.
     */
    @Test
    void omitsApparmorUnlessAProfileIsNamed() {
        assertThat(String.join(" ", arguments())).doesNotContain("apparmor");

        SandboxPolicy withApparmor = new SandboxPolicy(
                64, 67_108_864L, 256, "/nonexistent/seccomp.json", "codearena-sandbox");
        assertThat(pairs(withApparmor.containerArguments(LIMITS, "v", true, "/usr/bin")))
                .contains("--security-opt apparmor=codearena-sandbox");
    }

    @Test
    void mountsNothingFromTheHost() {
        // The only -v is the named workspace volume; a host path would contain a separator
        // before the colon.
        List<String> mounts = pairs(arguments()).stream()
                .filter(pair -> pair.startsWith("-v "))
                .toList();
        assertThat(mounts).hasSize(1);
        assertThat(mounts.getFirst()).doesNotContain("/var/run").doesNotContain("docker.sock");
    }

    /**
     * Joins each flag with its value so assertions read like the command line does, instead
     * of checking for a flag and its argument as unrelated list entries.
     */
    private static List<String> pairs(List<String> arguments) {
        return java.util.stream.IntStream.range(0, arguments.size())
                .filter(i -> arguments.get(i).startsWith("-"))
                .mapToObj(i -> i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("-")
                        ? arguments.get(i) + " " + arguments.get(i + 1)
                        : arguments.get(i))
                .toList();
    }
}
