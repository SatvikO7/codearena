package com.codearena.worker.judge;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionOutcome;
import com.codearena.shared.execution.ExecutionResult;
import com.codearena.worker.execution.ExecutionService;
import com.codearena.worker.judge.JudgeRepository.ClaimedSubmission;
import com.codearena.worker.judge.JudgeRepository.JudgeResult;
import com.codearena.worker.judge.JudgeRepository.JudgeTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How judging classifies failures that are ours rather than the submitter's.
 *
 * <h2>Why these are stubbed, when {@link JudgeVerdictIT} deliberately is not</h2>
 * {@code JudgeVerdictIT} runs real programs in real sandboxes, because a mock could be made
 * to return any outcome and the mapping would look right while the sandbox did something
 * else. That argument does not apply here: these are the paths where the <em>sandbox never
 * produced an outcome at all</em> — it was unreachable, or it failed. There is no real
 * execution to observe, and the only way to reach some of them with a live daemon is to
 * break one on purpose.
 *
 * <p>So the stub is the subject, not a shortcut. What is being asserted is that judging
 * reacts correctly to each way the execution layer can fail.
 */
class JudgeFailureClassificationTest {

    private static final List<JudgeTestCase> TESTS =
            List.of(new JudgeTestCase(0, "in", "out", 1, false));

    private static JudgeService judgeWith(ExecutionService executionService) {
        return new JudgeService(executionService, new OutputComparator(),
                20_000, 512, 1.0, 64, 65_536);
    }

    private static ClaimedSubmission submission() {
        return new ClaimedSubmission(1L, UUID.randomUUID(), Language.PYTHON,
                "print(1)", 1, java.time.Instant.now(), 1L, 1_000, 128);
    }

    /**
     * The important one.
     *
     * <p>A sandbox that could not be obtained means nothing was judged. Recording
     * SYSTEM_ERROR would permanently fail a submission because the machine was briefly full,
     * so the exception propagates instead and the recovery sweeper tries again — the same
     * path a worker that died mid-execution takes, bounded by the same attempt counter.
     */
    @Test
    void doesNotTurnATransientExecutorOutageIntoAVerdict() {
        JudgeService judge = judgeWith(failingPrepare(
                () -> new ExecutionService.ExecutionUnavailableException("executor returned 503")));

        assertThatThrownBy(() -> judge.judge(submission(), TESTS))
                .isInstanceOf(ExecutionService.ExecutionUnavailableException.class);
    }

    /**
     * Anything else from the execution layer is a judge failure, and a judge failure is a
     * SYSTEM_ERROR: terminal, and explicitly not a statement about the code.
     */
    @Test
    void reportsAnUnexpectedExecutionFailureAsASystemError() {
        JudgeService judge = judgeWith(failingPrepare(
                () -> new IllegalStateException("something unforeseen")));

        JudgeResult result = judge.judge(submission(), TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(result.errorMessage()).doesNotContain("something unforeseen");
    }

    /** An infrastructure failure during a run is a SYSTEM_ERROR, never a RUNTIME_ERROR. */
    @Test
    void reportsAnInfrastructureFailureDuringARunAsASystemError() {
        JudgeService judge = judgeWith(workspaceReturning(
                ExecutionResult.completed(0, "", "", 0),
                ExecutionResult.infrastructureFailure("daemon went away")));

        JudgeResult result = judge.judge(submission(), TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        // The detail is logged, never returned: it can name images and daemon paths.
        assertThat(result.errorMessage()).doesNotContain("daemon");
    }

    /** An infrastructure failure during compilation is likewise not a COMPILATION_ERROR. */
    @Test
    void reportsAnInfrastructureFailureDuringCompilationAsASystemError() {
        JudgeService judge = judgeWith(workspaceReturning(
                ExecutionResult.infrastructureFailure("daemon went away"),
                ExecutionResult.completed(0, "out", "", 1)));

        JudgeResult result = judge.judge(submission(), TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
    }

    /**
     * A program killed for writing an oversized file is a runtime failure with a reason
     * somebody can act on, rather than "exited with signal 25".
     */
    @Test
    void reportsAFileSizeKillAsARuntimeErrorThatExplainsItself() {
        JudgeService judge = judgeWith(workspaceReturning(
                ExecutionResult.completed(0, "", "", 0),
                ExecutionResult.killed(ExecutionOutcome.FILE_LIMIT_EXCEEDED, "", "", 5)));

        JudgeResult result = judge.judge(submission(), TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.RUNTIME_ERROR);
        assertThat(result.errorMessage()).contains("file larger than allowed");
    }

    /** The same during compilation is a COMPILATION_ERROR: the source caused it. */
    @Test
    void reportsACompilerFileSizeKillAsACompilationError() {
        JudgeService judge = judgeWith(workspaceReturning(
                ExecutionResult.killed(ExecutionOutcome.FILE_LIMIT_EXCEEDED, "", "", 5),
                ExecutionResult.completed(0, "out", "", 1)));

        JudgeResult result = judge.judge(submission(), TESTS);

        assertThat(result.status()).isEqualTo(SubmissionStatus.COMPILATION_ERROR);
        assertThat(result.errorMessage()).contains("larger than allowed");
    }

    /**
     * A problem with no test cases cannot have been the submitter's doing, so it must not
     * produce a verdict about their code.
     */
    @Test
    void reportsAProblemWithNoTestsAsASystemError() {
        JudgeResult result = judgeWith(workspaceReturning(
                ExecutionResult.completed(0, "", "", 0),
                ExecutionResult.completed(0, "out", "", 1)))
                .judge(submission(), List.of());

        assertThat(result.status()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
    }

    // ------------------------------------------------------------------ stubs

    private static ExecutionService failingPrepare(Supplier<RuntimeException> failure) {
        return (submissionId, language, source) -> {
            throw failure.get();
        };
    }

    private static ExecutionService workspaceReturning(ExecutionResult compilation,
                                                       ExecutionResult run) {
        return (submissionId, language, source) -> new ExecutionService.Workspace() {
            @Override
            public ExecutionResult compile(ExecutionLimits limits) {
                return compilation;
            }

            @Override
            public ExecutionResult run(String stdin, ExecutionLimits limits) {
                return run;
            }

            @Override
            public void close() {
            }
        };
    }
}
