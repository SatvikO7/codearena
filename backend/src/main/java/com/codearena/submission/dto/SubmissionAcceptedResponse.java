package com.codearena.submission.dto;

import com.codearena.shared.SubmissionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The immediate answer to a submission, returned as soon as the row is committed and
 * before any judging has happened.
 *
 * <p>Status is always QUEUED here. The client polls the detail endpoint from this point on.
 */
@Schema(description = "Acknowledgement that a submission was accepted into the queue.")
public record SubmissionAcceptedResponse(
        UUID submissionId,
        SubmissionStatus status,
        Instant createdAt) {
}
