package com.codearena.worker.judge;

import com.codearena.shared.SubmissionStatus;
import com.codearena.worker.execution.ExecutionLimits;
import com.codearena.worker.execution.ExecutionResult;
import com.codearena.worker.execution.ExecutionService;
import com.codearena.worker.judge.JudgeRepository.ClaimedSubmission;
import com.codearena.worker.judge.JudgeRepository.JudgeResult;
import com.codearena.worker.judge.JudgeRepository.JudgeTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a claimed submission into a verdict.
 *
 * <p>Compile once, then run once per test case, stopping at the first failure. Stopping
 * early is both faster and sufficient: the verdict is already decided, and continuing would
 * only spend containers confirming it.
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    /**
     * How much compiler or runtime output is kept.
     *
     * <p>Enough for a developer to see what went wrong; far short of what a program
     * emitting megabytes of diagnostics would produce. The rest is discarded rather than
     * stored, because nobody reads the four-thousandth line of a template error and
     * PostgreSQL should not be storing it.
     */
    private static final int STORED_MESSAGE_LIMIT = 4000;

    private final ExecutionService executionService;
    private final OutputComparator comparator;
    private final long compileTimeoutMs;
    private final int compileMemoryMb;
    private final double cpus;
    private final int pidsLimit;
    private final int outputLimitBytes;

    public JudgeService(ExecutionService executionService,
                        OutputComparator comparator,
                        @Value("${codearena.execution.compile-timeout-ms:20000}") long compileTimeoutMs,
                        @Value("${codearena.execution.compile-memory-mb:512}") int compileMemoryMb,
                        @Value("${codearena.execution.cpus:1.0}") double cpus,
                        @Value("${codearena.execution.pids-limit:64}") int pidsLimit,
                        @Value("${codearena.execution.output-limit-bytes:65536}") int outputLimitBytes) {
        this.executionService = executionService;
        this.comparator = comparator;
        this.compileTimeoutMs = compileTimeoutMs;
        this.compileMemoryMb = compileMemoryMb;
        this.cpus = cpus;
        this.pidsLimit = pidsLimit;
        this.outputLimitBytes = outputLimitBytes;
    }

    /**
     * Judges one submission.
     *
     * <p>The workspace is opened in a try-with-resources, so its volume and containers are
     * destroyed on every path out — including the ones taken when a test fails, when the
     * program is killed, and when the judge itself throws.
     */
    public JudgeResult judge(ClaimedSubmission submission, List<JudgeTestCase> testCases) {
        if (testCases.isEmpty()) {
            // Publication requires at least one test case, so reaching here means the
            // problem changed underneath the submission. Not the submitter's fault.
            log.error("event=JUDGE_NO_TESTS submission={} problem={}",
                    submission.publicId(), submission.problemId());
            return systemError("This problem has no test cases; the submission could not be judged.");
        }

        ExecutionLimits runLimits = new ExecutionLimits(
                submission.timeLimitMs(), submission.memoryLimitMb(), cpus, pidsLimit, outputLimitBytes);

        try (ExecutionService.Workspace workspace = executionService.prepare(
                submission.publicId().toString(), submission.language(), submission.sourceCode())) {

            ExecutionResult compilation = workspace.compile(
                    runLimits.forCompilation(compileTimeoutMs, compileMemoryMb));

            JudgeResult compilationFailure = interpretCompilation(submission, compilation, testCases.size());
            if (compilationFailure != null) {
                return compilationFailure;
            }

            return runTests(submission, workspace, testCases, runLimits);

        } catch (RuntimeException e) {
            // The judge broke, not the submission. Says so, and says nothing about the code.
            log.error("event=JUDGE_FAILED submission={} reason={}",
                    submission.publicId(), e.toString());
            return systemError("The judge could not run this submission.");
        }
    }

    /** @return a terminal result when compilation ended the judgement, or null to continue */
    private JudgeResult interpretCompilation(ClaimedSubmission submission,
                                             ExecutionResult compilation,
                                             int testCount) {
        switch (compilation.outcome()) {
            case COMPLETED -> {
                return null;
            }
            case NON_ZERO_EXIT -> {
                log.info("event=COMPILATION_FAILED submission={}", submission.publicId());
                // Compiler diagnostics are the one place a user legitimately sees tool
                // output. They are truncated and carry no host paths: the compiler runs
                // inside the sandbox with a fixed working directory and a fixed file name,
                // so the worst it can mention is /work/main.cpp.
                return new JudgeResult(SubmissionStatus.COMPILATION_ERROR,
                        testCount, 0, null, null, null,
                        truncate(preferStderr(compilation)));
            }
            case TIMED_OUT -> {
                return new JudgeResult(SubmissionStatus.COMPILATION_ERROR,
                        testCount, 0, null, null, null,
                        "Compilation exceeded the time limit.");
            }
            case OUT_OF_MEMORY -> {
                return new JudgeResult(SubmissionStatus.COMPILATION_ERROR,
                        testCount, 0, null, null, null,
                        "Compilation exceeded the memory limit.");
            }
            case OUTPUT_LIMIT_EXCEEDED -> {
                return new JudgeResult(SubmissionStatus.COMPILATION_ERROR,
                        testCount, 0, null, null, null,
                        "Compilation produced too much output.");
            }
            case INFRASTRUCTURE_FAILURE -> {
                log.error("event=COMPILATION_INFRA_FAILURE submission={} detail={}",
                        submission.publicId(), compilation.detail());
                return systemError("The judge could not compile this submission.");
            }
        }
        return systemError("Unrecognised compilation outcome.");
    }

    private JudgeResult runTests(ClaimedSubmission submission,
                                 ExecutionService.Workspace workspace,
                                 List<JudgeTestCase> testCases,
                                 ExecutionLimits limits) {
        int passed = 0;
        long slowestMs = 0;

        for (JudgeTestCase testCase : testCases) {
            // Only the input crosses into the container. The expected output stays here.
            ExecutionResult execution = workspace.run(testCase.input(), limits);
            slowestMs = Math.max(slowestMs, execution.durationMs());

            JudgeResult failure = interpretRun(submission, execution, testCase,
                    testCases.size(), passed, slowestMs);
            if (failure != null) {
                return failure;
            }
            passed++;
        }

        log.info("event=JUDGE_ACCEPTED submission={} tests={} slowestMs={}",
                submission.publicId(), testCases.size(), slowestMs);
        return new JudgeResult(SubmissionStatus.ACCEPTED,
                testCases.size(), passed, null, (int) slowestMs, null, null);
    }

    /** @return a terminal result when this test ended the judgement, or null to keep going */
    private JudgeResult interpretRun(ClaimedSubmission submission,
                                     ExecutionResult execution,
                                     JudgeTestCase testCase,
                                     int total,
                                     int passedSoFar,
                                     long slowestMs) {
        switch (execution.outcome()) {
            case TIMED_OUT -> {
                return verdict(SubmissionStatus.TIME_LIMIT_EXCEEDED, total, passedSoFar,
                        testCase.position(), slowestMs,
                        "Execution exceeded the time limit on test %d.".formatted(testCase.position() + 1));
            }
            case OUT_OF_MEMORY -> {
                return verdict(SubmissionStatus.MEMORY_LIMIT_EXCEEDED, total, passedSoFar,
                        testCase.position(), slowestMs,
                        "Execution exceeded the memory limit on test %d.".formatted(testCase.position() + 1));
            }
            case OUTPUT_LIMIT_EXCEEDED -> {
                // Treated as a runtime failure rather than a wrong answer: the program
                // misbehaved, and its output was never fully seen, so it cannot be compared.
                return verdict(SubmissionStatus.RUNTIME_ERROR, total, passedSoFar,
                        testCase.position(), slowestMs,
                        "Program produced more output than allowed on test %d.".formatted(testCase.position() + 1));
            }
            case NON_ZERO_EXIT -> {
                return verdict(SubmissionStatus.RUNTIME_ERROR, total, passedSoFar,
                        testCase.position(), slowestMs,
                        "Program exited with status %d on test %d.%s".formatted(
                                execution.exitCode(), testCase.position() + 1,
                                execution.stderr().isBlank() ? "" : "\n" + truncate(execution.stderr())));
            }
            case INFRASTRUCTURE_FAILURE -> {
                log.error("event=RUN_INFRA_FAILURE submission={} test={} detail={}",
                        submission.publicId(), testCase.position(), execution.detail());
                return systemError("The judge could not run this submission.");
            }
            case COMPLETED -> {
                if (!comparator.matches(testCase.expectedOutput(), execution.stdout())) {
                    log.info("event=JUDGE_WRONG_ANSWER submission={} failedTest={}",
                            submission.publicId(), testCase.position());
                    // The message names the test number and nothing else. Reporting what
                    // was expected, or what the program printed, would leak the answer key
                    // one submission at a time.
                    return verdict(SubmissionStatus.WRONG_ANSWER, total, passedSoFar,
                            testCase.position(), slowestMs,
                            "Wrong answer on test %d.".formatted(testCase.position() + 1));
                }
                return null;
            }
        }
        return systemError("Unrecognised execution outcome.");
    }

    private JudgeResult verdict(SubmissionStatus status, int total, int passed,
                                int failedIndex, long slowestMs, String message) {
        return new JudgeResult(status, total, passed, failedIndex, (int) slowestMs, null, message);
    }

    private JudgeResult systemError(String message) {
        return new JudgeResult(SubmissionStatus.SYSTEM_ERROR, null, null, null, null, null, message);
    }

    /** Compiler output goes to stderr; fall back to stdout for tools that disagree. */
    private String preferStderr(ExecutionResult result) {
        return result.stderr().isBlank() ? result.stdout() : result.stderr();
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        String stripped = message.strip();
        if (stripped.length() <= STORED_MESSAGE_LIMIT) {
            return stripped;
        }
        return stripped.substring(0, STORED_MESSAGE_LIMIT) + "\n… output truncated …";
    }
}
