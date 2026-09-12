package com.codearena.worker.execution;

import com.codearena.shared.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sandbox, exercised against a real Docker daemon.
 *
 * <p>Mocked out, none of this would mean anything: the questions here are whether the
 * kernel actually kills a program that allocates too much, whether a fork bomb is contained
 * by the pid limit, and whether a container with {@code --network none} really cannot
 * reach the network. Only a real daemon can answer those.
 *
 * <p>Requires the language images to be present. They are pulled once in {@link #pullImages}
 * rather than during a timed test, so a slow download cannot be mistaken for a slow program.
 */
class DockerExecutionIT {

    private static final int DOCKER_AVAILABLE_TIMEOUT_SECONDS = 30;

    private final DockerExecutionService executionService = new DockerExecutionService("docker", 64);

    /** Generous limits, so a test that fails does so for its own reason rather than on time. */
    private static final ExecutionLimits LIMITS = new ExecutionLimits(10_000, 256, 1.0, 64, 65_536);

    @BeforeAll
    static void pullImages() throws Exception {
        assumeTrue(dockerAvailable(), "Docker daemon is not available");
        for (String image : LanguageSpec.allImages()) {
            new ProcessBuilder("docker", "pull", image)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.MINUTES);
        }
    }

    // ------------------------------------------------------------------- happy paths

    @Test
    @Timeout(120)
    void runsAPythonProgramAndCapturesItsOutput() {
        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-py", Language.PYTHON, "print(sum(int(x) for x in input().split()))")) {

            assertThat(workspace.compile(LIMITS).succeeded()).isTrue();

            ExecutionResult result = workspace.run("2 3\n", LIMITS);

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);
            assertThat(result.stdout().strip()).isEqualTo("5");
        }
    }

    @Test
    @Timeout(180)
    void compilesAndRunsCpp() {
        String source = """
                #include <iostream>
                int main() { int a, b; std::cin >> a >> b; std::cout << a + b << std::endl; }
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-cpp", Language.CPP, source)) {
            assertThat(workspace.compile(LIMITS.forCompilation(60_000, 512)).succeeded()).isTrue();

            ExecutionResult result = workspace.run("7 8\n", LIMITS);

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);
            assertThat(result.stdout().strip()).isEqualTo("15");
        }
    }

    @Test
    @Timeout(180)
    void compilesAndRunsJava() {
        String source = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        System.out.println(in.nextInt() * in.nextInt());
                    }
                }
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-java", Language.JAVA, source)) {
            assertThat(workspace.compile(LIMITS.forCompilation(60_000, 512)).succeeded()).isTrue();

            ExecutionResult result = workspace.run("6 7\n", LIMITS);

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);
            assertThat(result.stdout().strip()).isEqualTo("42");
        }
    }

    // ------------------------------------------------------------------- failure paths

    @Test
    @Timeout(180)
    void reportsACompilationFailureWithoutTreatingItAsAJudgeError() {
        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-badcpp", Language.CPP, "int main() { this is not c++ }")) {

            ExecutionResult result = workspace.compile(LIMITS.forCompilation(60_000, 512));

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.NON_ZERO_EXIT);
            assertThat(result.stderr()).isNotBlank();
            // The diagnostic may name the source file, but only by its fixed sandbox path.
            assertThat(result.stderr()).doesNotContain("/home").doesNotContain("C:\\");
        }
    }

    @Test
    @Timeout(120)
    void reportsANonZeroExitAsARuntimeFailure() {
        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-crash", Language.PYTHON, "raise SystemExit(3)")) {

            ExecutionResult result = workspace.run("", LIMITS);

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.NON_ZERO_EXIT);
            assertThat(result.exitCode()).isEqualTo(3);
        }
    }

    /** An infinite loop never finishes, so it can only be stopped by the limit itself. */
    @Test
    @Timeout(120)
    void killsAProgramThatExceedsItsWallClockBudget() {
        ExecutionLimits oneSecond = new ExecutionLimits(1_000, 256, 1.0, 64, 65_536);

        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-loop", Language.PYTHON, "while True: pass")) {

            ExecutionResult result = workspace.run("", oneSecond);

            assertThat(result.outcome()).isEqualTo(ExecutionOutcome.TIMED_OUT);
        }
    }

    /** The kernel enforces this; nothing in Java could stop the allocation in time. */
    @Test
    @Timeout(180)
    void killsAProgramThatExceedsItsMemoryLimit() {
        ExecutionLimits small = new ExecutionLimits(15_000, 32, 1.0, 64, 65_536);

        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-memory", Language.PYTHON, "x = bytearray(512 * 1024 * 1024)")) {

            ExecutionResult result = workspace.run("", small);

            assertThat(result.outcome())
                    .as("a 512MB allocation under a 32MB ceiling must be killed")
                    .isIn(ExecutionOutcome.OUT_OF_MEMORY, ExecutionOutcome.NON_ZERO_EXIT);
        }
    }

    /** Unbounded output would fill the worker's heap long before any timeout fired. */
    @Test
    @Timeout(120)
    void stopsAProgramThatFloodsItsOutput() {
        ExecutionLimits tinyOutput = new ExecutionLimits(15_000, 256, 1.0, 64, 4_096);

        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-flood", Language.PYTHON, "while True: print('x' * 1000)")) {

            ExecutionResult result = workspace.run("", tinyOutput);

            assertThat(result.outcome()).isIn(
                    ExecutionOutcome.OUTPUT_LIMIT_EXCEEDED, ExecutionOutcome.TIMED_OUT);
            assertThat(result.stdout().length()).isLessThanOrEqualTo(8_192);
        }
    }

    // ------------------------------------------------------------------- containment

    /** No timeout catches a fork bomb; only the pid limit does. */
    @Test
    @Timeout(180)
    void containsAForkBomb() {
        ExecutionLimits fewPids = new ExecutionLimits(8_000, 128, 1.0, 16, 65_536);
        String source = """
                import os
                while True:
                    try:
                        os.fork()
                    except OSError:
                        pass
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-fork", Language.PYTHON, source)) {
            ExecutionResult result = workspace.run("", fewPids);

            // Contained one way or another; what matters is that it ended.
            assertThat(result.outcome()).isNotEqualTo(ExecutionOutcome.INFRASTRUCTURE_FAILURE);
        }
    }

    @Test
    @Timeout(120)
    void deniesNetworkAccess() {
        String source = """
                import socket
                try:
                    socket.create_connection(("1.1.1.1", 53), timeout=3)
                    print("NETWORK-REACHABLE")
                except Exception:
                    print("NETWORK-BLOCKED")
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-net", Language.PYTHON, source)) {
            ExecutionResult result = workspace.run("", LIMITS);

            assertThat(result.stdout()).contains("NETWORK-BLOCKED");
            assertThat(result.stdout()).doesNotContain("NETWORK-REACHABLE");
        }
    }

    @Test
    @Timeout(120)
    void deniesAccessToTheDockerSocket() {
        String source = """
                import os
                print("SOCKET-PRESENT" if os.path.exists("/var/run/docker.sock") else "SOCKET-ABSENT")
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-sock", Language.PYTHON, source)) {
            ExecutionResult result = workspace.run("", LIMITS);

            assertThat(result.stdout()).contains("SOCKET-ABSENT");
        }
    }

    /** Nothing the program writes may survive, and it must not be able to fill a disk. */
    @Test
    @Timeout(120)
    void givesTheProgramAReadOnlyRootFilesystem() {
        String source = """
                try:
                    open("/etc/codearena-probe", "w").write("x")
                    print("ROOTFS-WRITABLE")
                except OSError:
                    print("ROOTFS-READONLY")
                """;

        try (ExecutionService.Workspace workspace = executionService.prepare("test-ro", Language.PYTHON, source)) {
            ExecutionResult result = workspace.run("", LIMITS);

            assertThat(result.stdout()).contains("ROOTFS-READONLY");
        }
    }

    @Test
    @Timeout(120)
    void runsAsAnUnprivilegedUser() {
        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-uid", Language.PYTHON, "import os; print(os.getuid())")) {

            ExecutionResult result = workspace.run("", LIMITS);

            assertThat(result.stdout().strip()).isNotEqualTo("0");
        }
    }

    /** Closing a workspace must leave no volume behind, even after a failed run. */
    @Test
    @Timeout(120)
    void removesItsVolumeOnClose() throws Exception {
        long before = countJudgeVolumes();

        try (ExecutionService.Workspace workspace = executionService.prepare(
                "test-cleanup", Language.PYTHON, "raise SystemExit(1)")) {
            workspace.run("", LIMITS);
        }

        assertThat(countJudgeVolumes())
                .as("the workspace volume must be gone once the workspace is closed")
                .isLessThanOrEqualTo(before);
    }

    // ------------------------------------------------------------------- helpers

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return process.waitFor(DOCKER_AVAILABLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static long countJudgeVolumes() throws Exception {
        Process process = new ProcessBuilder("docker", "volume", "ls", "--quiet",
                "--filter", "name=codearena-ws-").start();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            long count = reader.lines().count();
            process.waitFor(30, TimeUnit.SECONDS);
            return count;
        }
    }
}
