package com.codearena.submission.dto;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.Submission;
import com.codearena.submission.SubmissionTestResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single submission, as its author sees it.
 *
 * <p><b>Source code is included here, and only here.</b> The decision, and why:
 * a user reviewing their own history needs to see what they wrote — a judge that showed
 * only "WRONG_ANSWER" without the code would be close to useless. The endpoint is scoped
 * to the owner, so the code returned is always the caller's own. It is deliberately absent
 * from the list response, where it would mean shipping tens of kilobytes per row for text
 * nobody reads at that level.
 *
 * <p>What is <b>never</b> here, no matter what happened during judging: any hidden test's
 * input or expected output, any container id, host path, environment variable or command
 * line. {@code errorMessage} carries compiler diagnostics or a short runtime message that
 * the worker has already truncated and sanitised; {@code failedTestIndex} and
 * {@code testResults} say <em>which</em> tests failed without saying anything about what
 * they contained.
 *
 * <p>Memory is deliberately absent. It is <em>enforced</em> — a program exceeding its
 * ceiling is killed by the kernel and reported as MEMORY_LIMIT_EXCEEDED — but peak usage is
 * not <em>measured</em>, and a field that was always null would be worse than no field. The
 * reason and the fix are in the README's known limitations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A submission and its verdict. Only the submission's author, or an administrator, may read one.")
public record SubmissionDetailResponse(
        UUID id,
        UUID problemId,
        String problemSlug,
        String problemTitle,
        Language language,
        SubmissionStatus status,

        @Schema(description = "The submitted source. Present only on this endpoint, and only for the owner.")
        String sourceCode,

        Integer testsTotal,
        Integer testsPassed,

        @Schema(description = "Zero-based index of the first failing test. The test's contents are never disclosed.")
        Integer failedTestIndex,

        Integer runtimeMs,

        @Schema(description = "Sanitised compiler or runtime output. Never contains hidden test data or host paths.")
        String errorMessage,

        @Schema(description = "Per-test outcomes, in test order. Empty until judging finishes. "
                            + "Never includes any test's input or expected output.")
        List<TestResultResponse> testResults,

        @Schema(description = "How many times a worker has claimed this submission. Greater than "
                            + "one means an earlier worker failed and it was retried.")
        int attempts,

        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public static SubmissionDetailResponse from(Submission submission, List<SubmissionTestResult> testResults) {
        return new SubmissionDetailResponse(
                submission.getPublicId(),
                submission.getProblem().getPublicId(),
                submission.getProblem().getSlug(),
                submission.getProblem().getTitle(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getSourceCode(),
                submission.getTestsTotal(),
                submission.getTestsPassed(),
                submission.getFailedTestIndex(),
                submission.getRuntimeMs(),
                submission.getErrorMessage(),
                testResults.stream().map(TestResultResponse::from).toList(),
                submission.getAttempts(),
                submission.getCreatedAt(),
                submission.getStartedAt(),
                submission.getFinishedAt(),
                submission.getUpdatedAt());
    }
}
