package com.codearena.executor.sandbox;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionOutcome;
import com.codearena.shared.execution.ExecutionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The execution-security regression suite.
 *
 * <h2>Why these tests are written this way</h2>
 * Every test here runs a <b>real malicious program</b> inside a <b>real sandbox</b> and
 * asserts what actually happened. None of them inspects the arguments passed to Docker.
 *
 * <p>That distinction is the entire value of the suite. Asserting that
 * {@code --cap-drop ALL} appears in an argument list proves that a string is in a list; it
 * would keep passing if the flag were misspelled, if the daemon ignored it, if a later
 * argument overrode it, or if the kernel did not support it. Asserting that a program which
 * tries to read another user's files gets {@code EACCES} proves the property the flag exists
 * for. {@code SandboxPolicyTest} covers the argument list separately, and deliberately
 * claims nothing about the kernel.
 *
 * <p>Where a control cannot be fully verified from inside the sandbox, the test says so in
 * its name and asserts what it can, rather than asserting something weaker under a
 * reassuring name.
 */
class SandboxSecurityIT {

    private static final int DOCKER_TIMEOUT_SECONDS = 30;

    private final DockerSandboxService sandbox = new DockerSandboxService(
            new SandboxPolicy(64, 67_108_864L, 256, SandboxIsolationIT.seccompProfilePath(), ""),
            // A throwaway registry: these suites are about isolation, not metrics.
            new SandboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            "docker");

    private static final ExecutionLimits LIMITS = new ExecutionLimits(15_000, 256, 1.0, 64, 65_536);

    @BeforeAll
    static void requireSandboxImages() {
        assumeTrue(dockerAvailable(), "Docker daemon is not available");
        for (String image : LanguageSpec.allImages()) {
            assumeTrue(imagePresent(image),
                    "Sandbox image " + image + " is missing. Run sandbox/build-images.sh");
        }
    }

    // ===================================================================== networking

    /**
     * DNS is not merely unreachable: there is no resolver and no interface to reach one
     * with. A sandbox that could resolve names would be one step from exfiltrating a
     * problem's test data through a hostname.
     */
    @Test
    @Timeout(180)
    void cannotResolveDns() {
        ExecutionResult result = runPython("""
                import socket
                try:
                    print("RESOLVED", socket.gethostbyname("example.com"))
                except Exception as e:
                    print("DNS_FAILED", type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("RESOLVED");
        assertThat(result.stdout()).contains("DNS_FAILED");
    }

    /** UDP has no more of a network than TCP does. */
    @Test
    @Timeout(180)
    void cannotSendUdp() {
        ExecutionResult result = runPython("""
                import socket
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.settimeout(3)
                    s.sendto(b"x", ("8.8.8.8", 53))
                    print("UDP_SENT")
                except Exception as e:
                    print("UDP_FAILED", type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("UDP_SENT");
        assertThat(result.stdout()).contains("UDP_FAILED");
    }

    /**
     * The only interface is loopback, so nothing else on the host's networks exists as far
     * as the sandbox is concerned — including the other containers in the compose stack.
     */
    @Test
    @Timeout(180)
    void cannotReachOtherContainersOnTheHostNetwork() {
        ExecutionResult result = runPython("""
                import socket
                targets = [("postgres", 5432), ("redis", 6379), ("executor", 8082),
                           ("172.17.0.1", 2375), ("127.0.0.1", 8082)]
                for host, port in targets:
                    try:
                        s = socket.create_connection((host, port), timeout=2)
                        print("CONNECTED", host, port)
                        s.close()
                    except Exception as e:
                        print("refused", host, port, type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("CONNECTED");
    }

    // ================================================================ the docker daemon

    /**
     * The socket is not in the sandbox, and neither is a client for it.
     *
     * <p>This is the control that keeps a compromised <em>submission</em> away from the
     * runtime. It is not the same question as whether the execution service holds the
     * socket — it does, deliberately, and docs/threat-model.md covers that separately.
     */
    @Test
    @Timeout(180)
    void cannotSeeTheDockerSocketOrAClientForIt() {
        ExecutionResult result = runPython("""
                import os, shutil
                for path in ("/var/run/docker.sock", "/run/docker.sock"):
                    print(path, "EXISTS" if os.path.exists(path) else "absent")
                print("docker-cli", "PRESENT" if shutil.which("docker") else "absent")
                """);

        assertThat(result.stdout()).doesNotContain("EXISTS");
        assertThat(result.stdout()).doesNotContain("PRESENT");
    }

    // ============================================================ filesystem and secrets

    /**
     * The sandbox sees the image's filesystem and its own workspace. Nothing of the host,
     * nothing of the execution service, nothing of the other services.
     */
    @Test
    @Timeout(180)
    void cannotReachHostOrServiceFilesystems() {
        ExecutionResult result = runPython("""
                import os
                probes = ["/host", "/hostfs", "/app/app.jar", "/app/seccomp/codearena.json",
                          "/var/lib/postgresql", "/etc/codearena", "/root/.docker/config.json",
                          "/var/run/secrets", "/proc/1/root/etc/shadow"]
                for p in probes:
                    try:
                        if os.path.isdir(p):
                            print("DIR_READABLE", p)
                        else:
                            with open(p, "rb") as fh:
                                fh.read(1)
                            print("FILE_READABLE", p)
                    except Exception as e:
                        print("denied", p, type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("DIR_READABLE");
        assertThat(result.stdout()).doesNotContain("FILE_READABLE");
    }

    /**
     * The sandbox environment is constructed, never inherited.
     *
     * <p>The execution service's own process holds an executor token, and in other
     * deployments would sit beside services holding database and Redis credentials. A
     * container that inherited its parent's environment would hand all of that to the first
     * program that printed {@code os.environ}.
     */
    @Test
    @Timeout(180)
    void environmentContainsNothingButTheFixedSandboxVariables() {
        ExecutionResult result = runPython("""
                import os
                for key in sorted(os.environ):
                    print(f"{key}={os.environ[key]}")
                """);

        // Every variable that changes how a program behaves carries the value we chose,
        // whatever the image declared.
        assertThat(result.stdout())
                .contains("HOME=/tmp")
                .contains("TMPDIR=/tmp")
                .contains("LANG=C.UTF-8")
                .contains("LC_ALL=C.UTF-8")
                .contains("TZ=UTC");

        // `docker run --env` adds to the image's environment rather than replacing it, so
        // names declared by the base image survive. The sandbox images blank their values,
        // and this asserts exactly that: no variable may carry a non-empty value unless the
        // sandbox deliberately set it.
        List<String> deliberate = List.of("HOME", "TMPDIR", "LANG", "LC_ALL", "LANGUAGE",
                                          "TZ", "PATH", "HOSTNAME", "JAVA_HOME");
        List<String> unexpected = result.stdout().lines()
                .filter(line -> line.contains("="))
                .filter(line -> !line.split("=", 2)[1].isBlank())
                .map(line -> line.split("=", 2)[0])
                .filter(key -> !deliberate.contains(key))
                .toList();
        assertThat(unexpected)
                .as("no variable outside the sandbox's own set may carry a value")
                .isEmpty();

        String all = result.stdout().toUpperCase();
        for (String secret : List.of("TOKEN", "PASSWORD", "SECRET", "DATABASE", "REDIS",
                                     "POSTGRES", "CREDENTIAL", "DOCKER_HOST", "AWS_")) {
            assertThat(all).as("environment must not mention %s", secret).doesNotContain(secret);
        }

        // PID 1 is the container's init, in the same environment, and is the obvious next
        // place to look for whatever os.environ might not show.
        ExecutionResult viaPidOne = runPython(PID_ONE_ENVIRON);
        String pidOneEnv = viaPidOne.stdout().toUpperCase();
        for (String secret : List.of("TOKEN", "PASSWORD", "SECRET", "POSTGRES", "REDIS")) {
            assertThat(pidOneEnv)
                    .as("PID 1's environment must not mention %s either", secret)
                    .doesNotContain(secret);
        }
    }

    private static final String PID_ONE_ENVIRON =
            "with open('/proc/1/environ', 'rb') as fh:\n"
            + "    print(fh.read().decode('utf-8', 'replace'))\n";

    /**
     * Path traversal and symlinks cannot reach outside the mount namespace, because there is
     * nothing outside it to reach: the sandbox has no host mount to traverse into.
     */
    @Test
    @Timeout(180)
    void cannotEscapeTheWorkspaceByTraversalOrSymlink() {
        ExecutionResult result = runPython("""
                import os
                try:
                    os.symlink("/", "/tmp/root_link")
                    entries = sorted(os.listdir("/tmp/root_link"))
                    print("LINKED", entries)
                except Exception as e:
                    print("symlink_failed", type(e).__name__)

                for path in ("../../../../etc/shadow", "/work/../../etc/shadow", "/etc/shadow"):
                    try:
                        with open(path, "rb") as fh:
                            fh.read(1)
                        print("SHADOW_READABLE", path)
                    except Exception as e:
                        print("denied", path, type(e).__name__)
                """);

        // A symlink to / may well be created -- it resolves to the container's own root,
        // which is the image, not the host. What must never happen is reading a protected
        // host file through it.
        assertThat(result.stdout()).doesNotContain("SHADOW_READABLE");
    }

    /** The root filesystem is read-only; /tmp is the only writable place, and it is capped. */
    @Test
    @Timeout(180)
    void cannotWriteOutsideTmp() {
        ExecutionResult result = runPython("""
                import os
                for path in ("/evil", "/etc/evil", "/usr/bin/evil", "/work/evil"):
                    try:
                        with open(path, "w") as fh:
                            fh.write("x")
                        print("WROTE", path)
                    except Exception as e:
                        print("denied", path, type(e).__name__)
                try:
                    with open("/tmp/ok", "w") as fh:
                        fh.write("x")
                    print("tmp_writable")
                except Exception as e:
                    print("TMP_NOT_WRITABLE", type(e).__name__)
                """);

        // /work is mounted read-only for runs, so even the submission's own workspace is
        // immutable once judging starts.
        assertThat(result.stdout()).doesNotContain("WROTE");
        assertThat(result.stdout()).contains("tmp_writable");
    }

    /**
     * A program that writes until the disk gives out is contained by the tmpfs cap, and the
     * tmpfs is charged to the container's memory cgroup — so the failure is the container
     * dying, never the host filling up.
     */
    @Test
    @Timeout(240)
    void cannotExhaustHostDiskByWritingToTmp() {
        ExecutionResult result = runPython("""
                written = 0
                try:
                    with open("/tmp/flood", "wb") as fh:
                        chunk = b"x" * (1024 * 1024)
                        while True:
                            fh.write(chunk)
                            fh.flush()
                            written += len(chunk)
                            if written > 4 * 1024 * 1024 * 1024:
                                print("UNBOUNDED")
                                break
                except Exception as e:
                    print("stopped_at_mb", written // (1024 * 1024), type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("UNBOUNDED");
        // Either the tmpfs filled (an exception) or the cgroup killed it for the memory the
        // tmpfs pages count against. Both are containment; neither touches the host disk.
        assertThat(result.outcome())
                .isIn(ExecutionOutcome.COMPLETED, ExecutionOutcome.NON_ZERO_EXIT,
                      ExecutionOutcome.OUT_OF_MEMORY, ExecutionOutcome.TIMED_OUT);
    }

    // =================================================================== privileges

    /** Runs as nobody, holds no capabilities, and cannot acquire any. */
    @Test
    @Timeout(180)
    void runsUnprivilegedAndCannotEscalate() {
        ExecutionResult result = runPython("""
                import os
                print("uid", os.getuid(), "gid", os.getgid())
                try:
                    os.setuid(0)
                    print("SETUID_SUCCEEDED")
                except Exception as e:
                    print("setuid_denied", type(e).__name__)

                # CapEff is the effective capability set as a hex mask. All zeroes means
                # the process holds none at all.
                try:
                    with open("/proc/self/status") as fh:
                        for line in fh:
                            if line.startswith(("CapEff", "CapPrm", "CapBnd", "NoNewPrivs")):
                                print(line.strip())
                except Exception as e:
                    print("status_unreadable", type(e).__name__)
                """);

        assertThat(result.stdout()).contains("uid 65534");
        assertThat(result.stdout()).doesNotContain("SETUID_SUCCEEDED");
        assertThat(result.stdout()).contains("NoNewPrivs:\t1");
        // Every capability set empty: nothing held, and nothing obtainable via the bounding
        // set either.
        assertThat(result.stdout()).contains("CapEff:\t0000000000000000");
        assertThat(result.stdout()).contains("CapPrm:\t0000000000000000");
        assertThat(result.stdout()).contains("CapBnd:\t0000000000000000");
    }

    /**
     * The seccomp profile denies syscalls the daemon's default permits.
     *
     * <p>{@code ptrace} is the clearest evidence: it is not capability-gated for a process
     * tracing its own children, so dropping capabilities does not block it. Under Docker's
     * default profile it succeeds; under ours it returns EPERM. If this test fails, the
     * custom profile is not being applied and the sandbox has quietly fallen back.
     */
    @Test
    @Timeout(180)
    void seccompDeniesSyscallsTheDefaultProfileAllows() {
        ExecutionResult result = runPython("""
                import ctypes, errno
                libc = ctypes.CDLL(None, use_errno=True)
                # x86-64 syscall numbers.
                for name, number in (("ptrace", 101), ("userfaultfd", 323),
                                     ("perf_event_open", 298), ("io_uring_setup", 425)):
                    ctypes.set_errno(0)
                    rc = libc.syscall(number, 0, 0, 0, 0, 0, 0)
                    print(name, "rc", rc, "errno", ctypes.get_errno())
                """);

        assertThat(result.stdout()).as("the custom seccomp profile must be applied")
                .containsPattern("ptrace rc -1 errno 1");
    }

    /** No block or character devices beyond the harmless defaults Docker provides. */
    @Test
    @Timeout(180)
    void cannotAccessHostDevices() {
        ExecutionResult result = runPython("""
                import os
                for path in ("/dev/sda", "/dev/nvme0n1", "/dev/mem", "/dev/kmsg",
                             "/dev/port", "/dev/kvm", "/dev/dri"):
                    try:
                        fd = os.open(path, os.O_RDONLY)
                        os.close(fd)
                        print("OPENED", path)
                    except Exception as e:
                        print("denied", path, type(e).__name__)
                print("devices", sorted(os.listdir("/dev")))
                """);

        assertThat(result.stdout()).doesNotContain("OPENED");
    }

    // ================================================================ process behaviour

    /**
     * Nothing survives a timeout, including a child deliberately orphaned to outlive its
     * parent. Killing the container kills the cgroup, so there is no process left to survive.
     */
    @Test
    @Timeout(240)
    void leavesNoProcessOrContainerBehindAfterATimeout() throws Exception {
        int containersBefore = countSandboxContainers();

        ExecutionResult result = runPython("""
                import os, sys, time
                pid = os.fork()
                if pid == 0:
                    # The child detaches and would outlive the parent if anything let it.
                    os.setsid()
                    while True:
                        time.sleep(1)
                else:
                    print("forked", flush=True)
                    sys.exit(0)
                """, new ExecutionLimits(4_000, 256, 1.0, 64, 65_536));

        // The parent exits immediately but the container stays alive while the orphan runs,
        // so the wall-clock limit is what ends it.
        assertThat(result.outcome()).isIn(ExecutionOutcome.TIMED_OUT, ExecutionOutcome.COMPLETED);

        // Give the daemon a moment to finish removing the container.
        Thread.sleep(2_000);
        assertThat(countSandboxContainers())
                .as("a timed-out execution must leave no container behind")
                .isLessThanOrEqualTo(containersBefore);
    }

    /** The pid ceiling is per-container, so threads cannot buy their way past it either. */
    @Test
    @Timeout(240)
    void boundsThreadCreationAsWellAsProcesses() {
        ExecutionResult result = runPython("""
                import threading, time
                started = 0
                def idle():
                    time.sleep(30)
                try:
                    while True:
                        threading.Thread(target=idle, daemon=True).start()
                        started += 1
                        if started > 500:
                            print("UNBOUNDED", started)
                            break
                except Exception as e:
                    print("stopped_at", started, type(e).__name__)
                """);

        assertThat(result.stdout()).doesNotContain("UNBOUNDED");
    }

    /**
     * A CPU quota is a share of time, not a count of processes: spawning more workers must
     * not buy more CPU than the container was granted.
     *
     * <p>The assertion is deliberately loose. This measures wall-clock against a quota on a
     * developer machine with other things running; a tight bound would be flaky without
     * proving anything more. What matters is the order of magnitude — four processes sharing
     * a one-CPU quota must not finish in a quarter of the time.
     */
    @Test
    @Timeout(300)
    void cpuQuotaIsNotBypassedBySpawningMoreProcesses() {
        // A fixed amount of CPU *work*, not a wall-clock deadline. Spinning until a clock
        // reading would finish at the same moment however little CPU the container was
        // granted, which would prove nothing about the quota at all.
        String workload = """
                import os, time
                WORK = 4000000
                def burn():
                    total = 0
                    for i in range(WORK):
                        total += i * i
                    return total
                start = time.time()
                children = []
                for _ in range(%d):
                    pid = os.fork()
                    if pid == 0:
                        burn()
                        os._exit(0)
                    children.append(pid)
                for pid in children:
                    os.waitpid(pid, 0)
                print("elapsed", round(time.time() - start, 3))
                """;

        ExecutionLimits quota = new ExecutionLimits(120_000, 256, 0.5, 64, 65_536);
        ExecutionResult one = runPython(workload.formatted(1), quota);
        ExecutionResult four = runPython(workload.formatted(4), quota);

        assertThat(one.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);
        assertThat(four.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);

        double elapsedOne = Double.parseDouble(value(one.stdout(), "elapsed"));
        double elapsedFour = Double.parseDouble(value(four.stdout(), "elapsed"));

        // Four times the work under the same quota takes materially longer. The bound is
        // deliberately loose: this runs on a shared developer machine, and a tight ratio
        // would be flaky without proving more than the order of magnitude already does.
        assertThat(elapsedFour)
                .as("one unit of work took %.3fs and four units took %.3fs under the same "
                    + "0.5 CPU quota; extra processes must not buy extra CPU",
                    elapsedOne, elapsedFour)
                .isGreaterThan(elapsedOne * 2.0);
    }

    // ================================================================ compiler as a target

    /**
     * Compilation is untrusted-code processing and gets its own ceilings.
     *
     * <p>Template recursion is the classic way to make a compiler burn minutes of CPU and
     * gigabytes of memory without ever running a line of the program. It must fail as a
     * compilation outcome, not as a judge failure, and it must fail inside its budget.
     */
    @Test
    @Timeout(300)
    void boundsACompilerBombWithoutFailingTheJudge() {
        String source = """
                template<int N> struct Explode {
                    static const long long value = Explode<N - 1>::value + Explode<N - 1>::value;
                };
                template<> struct Explode<0> { static const long long value = 1; };
                int main() { return (int) Explode<400>::value; }
                """;

        try (SandboxService.Workspace workspace = sandbox.prepare("compiler-bomb", Language.CPP, source)) {
            ExecutionResult compilation = workspace.compile(
                    new ExecutionLimits(20_000, 256, 1.0, 64, 65_536));

            // Whatever the compiler does -- diagnose it, run out of memory, run out of time
            // -- it must not come back as an infrastructure failure, because that would
            // report SYSTEM_ERROR and tell the submitter the judge was broken.
            assertThat(compilation.outcome())
                    .as("a compiler bomb is a compilation outcome, not a judge failure")
                    .isNotEqualTo(ExecutionOutcome.INFRASTRUCTURE_FAILURE);
            assertThat(compilation.succeeded()).isFalse();
        }
    }

    // =================================================================== concurrency

    /**
     * Concurrent submissions cannot see or disturb each other.
     *
     * <p>Each writes a value unique to itself into its own workspace and reads it back. A
     * shared workspace, a reused volume name or a crossed output stream would show up as one
     * sandbox reading another's marker.
     */
    @Test
    @Timeout(300)
    void concurrentSandboxesAreIsolatedFromOneAnother() {
        int concurrency = 6;
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            String marker = "MARKER-" + i + "-" + java.util.UUID.randomUUID();
            futures.add(CompletableFuture.supplyAsync(() -> {
                String source = """
                        import os
                        marker = "%s"
                        with open("/tmp/marker", "w") as fh:
                            fh.write(marker)
                        with open("/tmp/marker") as fh:
                            print("read", fh.read())
                        print("workdir", sorted(os.listdir("/work")))
                        """.formatted(marker);
                try (SandboxService.Workspace workspace =
                             sandbox.prepare("concurrent", Language.PYTHON, source)) {
                    workspace.compile(LIMITS);
                    return workspace.run("", LIMITS).stdout();
                }
            }));
        }

        List<String> outputs = futures.stream().map(CompletableFuture::join).toList();

        assertThat(outputs).hasSize(concurrency);
        for (int i = 0; i < concurrency; i++) {
            String output = outputs.get(i);
            assertThat(output).as("sandbox %d read back its own marker", i).contains("MARKER-" + i + "-");
            // Its workspace holds its own source and nothing else -- no other run's files.
            assertThat(output).contains("workdir ['main.py']");
            for (int other = 0; other < concurrency; other++) {
                if (other != i) {
                    assertThat(output)
                            .as("sandbox %d must not see sandbox %d's marker", i, other)
                            .doesNotContain("MARKER-" + other + "-");
                }
            }
        }
    }

    // =================================================================== determinism

    /**
     * The sandbox environment is fixed, so a verdict does not depend on how the host is
     * configured. Locale and timezone are the two that silently change program output —
     * number formatting and date printing both follow them.
     */
    @Test
    @Timeout(180)
    void presentsADeterministicEnvironmentRegardlessOfTheHost() {
        ExecutionResult result = runPython("""
                import locale, os, time, socket
                print("TZ", os.environ.get("TZ"))
                print("LANG", os.environ.get("LANG"))
                print("cwd", os.getcwd())
                print("hostname", socket.gethostname())
                print("tzname", time.tzname[0])
                """);

        assertThat(result.stdout()).contains("TZ UTC");
        assertThat(result.stdout()).contains("LANG C.UTF-8");
        assertThat(result.stdout()).contains("cwd /work");
        // A fixed hostname: the default is the container id, which tells a program exactly
        // which container to ask about if it ever found something to ask.
        assertThat(result.stdout()).contains("hostname sandbox");
        assertThat(result.stdout()).contains("tzname UTC");
    }

    // ================================================================ kernel visibility

    /**
     * Documents what a container genuinely cannot hide, rather than pretending otherwise.
     *
     * <p>A container shares the host kernel, so its version is visible and always will be.
     * This test exists to assert the boundary as it really is: kernel identity is readable,
     * host processes are not. Claiming VM-level isolation from a Docker container would be
     * false, and a test that asserted it would have to be wrong.
     */
    @Test
    @Timeout(180)
    void cannotSeeHostProcessesEvenThoughTheKernelIsShared() {
        ExecutionResult result = runPython("""
                import os, platform
                pids = sorted(int(p) for p in os.listdir("/proc") if p.isdigit())
                print("pid_count", len(pids))
                print("max_pid", max(pids))
                print("kernel_visible", bool(platform.release()))
                """);

        // Its own PID namespace: a handful of processes, all of them ours. A host-shared
        // namespace would show hundreds and would include processes with much higher pids.
        int pidCount = Integer.parseInt(value(result.stdout(), "pid_count"));
        assertThat(pidCount)
                .as("the sandbox must have its own PID namespace")
                .isLessThan(10);
        // Stated, not asserted away: the kernel is shared and its identity is readable.
        assertThat(result.stdout()).contains("kernel_visible True");
    }

    // ===================================================================== helpers

    private static String value(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key + " "))
                .map(line -> line.substring(key.length() + 1).strip())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + key + "' in output:\n" + output));
    }

    private ExecutionResult runPython(String source) {
        return runPython(source, LIMITS);
    }

    private ExecutionResult runPython(String source, ExecutionLimits limits) {
        try (SandboxService.Workspace workspace = sandbox.prepare("security", Language.PYTHON, source)) {
            workspace.compile(limits);
            return workspace.run("", limits);
        }
    }

    private static int countSandboxContainers() throws Exception {
        Process process = new ProcessBuilder(
                "docker", "ps", "--all", "--quiet",
                "--filter", "label=" + SandboxPolicy.OWNER_LABEL + "=true")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return (int) output.lines().filter(line -> !line.isBlank()).count();
    }

    private static boolean imagePresent(String image) {
        return succeeds("docker", "image", "inspect", image);
    }

    private static boolean dockerAvailable() {
        return succeeds("docker", "version", "--format", "{{.Server.APIVersion}}");
    }

    private static boolean succeeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
