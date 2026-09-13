package com.codearena.worker.judge;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.executor.sandbox.DockerSandboxService;
import com.codearena.executor.sandbox.SandboxMetrics;
import com.codearena.executor.sandbox.SandboxPolicy;
import com.codearena.worker.judge.JudgeRepository.ClaimedSubmission;
import com.codearena.worker.judge.JudgeRepository.JudgeResult;
import com.codearena.worker.judge.JudgeRepository.JudgeTestCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every verdict the judge can reach, produced by really compiling and running a program.
 *
 * <p>This is the test that matters most in the phase. A mocked execution service could be
 * made to return any outcome, and the mapping from outcome to verdict would look correct
 * while the sandbox produced something else entirely. Here the programs are real, the
 * containers are real, and the verdicts are whatever actually happens.
 */
class JudgeVerdictIT {

    private static final int TIME_LIMIT_MS = 2_000;
    private static final int MEMORY_LIMIT_MB = 128;

    private final JudgeService judge = new JudgeService(
            new SandboxBackedExecutionService(new DockerSandboxService(
                    new SandboxPolicy(64, 67_108_864L, 256,
                            SandboxBackedExecutionService.seccompProfilePath(), ""),
                    new SandboxMetrics(),
                    "docker")),
            new OutputComparator(),
            60_000,     // compile timeout: generous, so a cold g++ is not mistaken for a hang
            512,        // compile memory
            1.0,        // cpus
            64,         // pids
            65_536);    // output limit

    /** Adds two integers. One visible test, one hidden, as a real problem would have. */
    private static final List<JudgeTestCase> ADDITION_TESTS = List.of(
            new JudgeTestCase(0, "2 3\n", "5", 1, false),
            new JudgeTestCase(1, "10 32\n", "42", 1, true));

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker daemon is not available");
    }

    // ------------------------------------------------------------------- ACCEPTED

    @Test
    @Timeout(300)
    void acceptsACorrectPythonSolution() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "a, b = map(int, input().split())\nprint(a + b)"),
                ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(result.testsPassed()).isEqualTo(2);
        assertThat(result.testsTotal()).isEqualTo(2);
        assertThat(result.failedTestIndex()).isNull();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.runtimeMs()).isNotNull().isPositive();
    }

    @Test
    @Timeout(300)
    void acceptsACorrectCppSolution() {
        JudgeResult result = judge.judge(
                submission(Language.CPP, """
                        #include <iostream>
                        int main() { long long a, b; std::cin >> a >> b; std::cout << a + b << "\\n"; }
                        """),
                ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(result.testsPassed()).isEqualTo(2);
    }

    @Test
    @Timeout(300)
    void acceptsACorrectJavaSolution() {
        JudgeResult result = judge.judge(
                submission(Language.JAVA, """
                        import java.util.Scanner;
                        public class Main {
                            public static void main(String[] args) {
                                Scanner in = new Scanner(System.in);
                                System.out.println(in.nextLong() + in.nextLong());
                            }
                        }
                        """),
                ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(result.testsPassed()).isEqualTo(2);
    }

    /** The comparison policy, end to end: trailing whitespace must not fail a solution. */
    @Test
    @Timeout(300)
    void acceptsOutputThatDiffersOnlyInTrailingWhitespace() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "a, b = map(int, input().split())\nprint(f'{a + b}   ')"),
                ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.ACCEPTED);
    }

    // --------------------------------------------------------------- WRONG_ANSWER

    @Test
    @Timeout(300)
    void reportsAWrongAnswerAndWhichTestFailedFirst() {
        // Correct for the first test, wrong for the second.
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, """
                        a, b = map(int, input().split())
                        print(5 if a == 2 else 0)
                        """),
                ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(result.testsPassed()).isEqualTo(1);
        assertThat(result.failedTestIndex()).isEqualTo(1);
    }

    /**
     * The verdict may say which test failed and must say nothing else about it.
     *
     * <p>Reporting the expected output, or what the program printed, would hand out the
     * answer key one submission at a time.
     */
    @Test
    @Timeout(300)
    void aWrongAnswerNeverDisclosesTheExpectedOutput() {
        List<JudgeTestCase> secretTests = List.of(
                new JudgeTestCase(0, "SECRET-INPUT-MARKER\n", "SECRET-ANSWER-MARKER", 1, true));

        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "print('definitely not the answer')"), secretTests);

        assertThat(result.status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
        assertThat(result.errorMessage())
                .doesNotContain("SECRET-ANSWER-MARKER")
                .doesNotContain("SECRET-INPUT-MARKER")
                .doesNotContain("definitely not the answer");
    }

    // ---------------------------------------------------------- COMPILATION_ERROR

    @Test
    @Timeout(300)
    void reportsACompilationErrorForInvalidCpp() {
        JudgeResult result = judge.judge(
                submission(Language.CPP, "int main() { this is not valid c++ }"), ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.COMPILATION_ERROR);
        assertThat(result.testsPassed()).isZero();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    @Timeout(300)
    void reportsACompilationErrorForInvalidJava() {
        JudgeResult result = judge.judge(
                submission(Language.JAVA, "public class Main { oops }"), ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.COMPILATION_ERROR);
        assertThat(result.errorMessage()).isNotBlank();
    }

    /**
     * Python has no compile step, so a syntax error surfaces when the program runs.
     * RUNTIME_ERROR is the honest verdict for an interpreted language.
     */
    @Test
    @Timeout(300)
    void reportsBrokenPythonAsARuntimeError() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "def oops(:\n    pass"), ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.RUNTIME_ERROR);
    }

    // -------------------------------------------------------------- RUNTIME_ERROR

    @Test
    @Timeout(300)
    void reportsARuntimeErrorWhenTheProgramCrashes() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "raise ValueError('boom')"), ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.RUNTIME_ERROR);
        assertThat(result.failedTestIndex()).isZero();
    }

    // -------------------------------------------------------- TIME_LIMIT_EXCEEDED

    @Test
    @Timeout(300)
    void reportsATimeLimitExceededForAnInfiniteLoop() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "while True:\n    pass"), ADDITION_TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.TIME_LIMIT_EXCEEDED);
        assertThat(result.errorMessage()).contains("time limit");
    }

    // ------------------------------------------------------ MEMORY_LIMIT_EXCEEDED

    @Test
    @Timeout(300)
    void reportsAMemoryOrRuntimeFailureForARunawayAllocation() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "x = bytearray(1024 * 1024 * 1024)"), ADDITION_TESTS);

        // The kernel's OOM kill surfaces as either a kill signal or the interpreter's own
        // MemoryError depending on where the allocation fails; both are correct refusals,
        // and neither is ACCEPTED.
        assertThat(result.status())
                .isIn(SubmissionStatus.MEMORY_LIMIT_EXCEEDED, SubmissionStatus.RUNTIME_ERROR);
    }

    // --------------------------------------------------------------- SYSTEM_ERROR

    /** A problem with no tests is a judge problem, not a verdict on the code. */
    @Test
    @Timeout(120)
    void reportsASystemErrorWhenThereAreNoTestCases() {
        JudgeResult result = judge.judge(
                submission(Language.PYTHON, "print(1)"), List.of());

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(result.errorMessage()).isNotBlank();
    }

    // ------------------------------------------------------------------- helpers

    private ClaimedSubmission submission(Language language, String source) {
        return new ClaimedSubmission(
                1L, UUID.randomUUID(), language, source, 1, 1L, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
