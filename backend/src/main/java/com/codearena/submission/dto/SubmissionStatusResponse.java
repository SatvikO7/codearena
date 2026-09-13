package com.codearena.submission.dto;

import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.Submission;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The payload pushed over SSE, and the lightest view of a submission.
 *
 * <p>Carries no source code: a status update is watched by a page that already has the code
 * on screen, and re-sending kilobytes of it on every transition would be waste. It carries
 * {@code updatedAt} because that is the field a client uses to discard an event older than
 * what it already holds — the rule that makes out-of-order and duplicate delivery converge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A submission's current state. The payload of the `submission` SSE event.")
public record SubmissionStatusResponse(
        UUID id,
        SubmissionStatus status,

        @Schema(description = "True once the verdict is final and no further events will be sent.")
        boolean terminal,

        Integer testsTotal,
        Integer testsPassed,
        Integer failedTestIndex,
        Integer runtimeMs,
        String errorMessage,

        Instant startedAt,
        Instant finishedAt,

        @Schema(description = "When the row last changed. Clients discard events older than "
                            + "the newest they have already applied.")
        Instant updatedAt) {

    public static SubmissionStatusResponse from(Submission submission) {
        return new SubmissionStatusResponse(
                submission.getPublicId(),
                submission.getStatus(),
                submission.getStatus().isTerminal(),
                submission.getTestsTotal(),
                submission.getTestsPassed(),
                submission.getFailedTestIndex(),
                submission.getRuntimeMs(),
                submission.getErrorMessage(),
                submission.getStartedAt(),
                submission.getFinishedAt(),
                submission.getUpdatedAt());
    }
}
