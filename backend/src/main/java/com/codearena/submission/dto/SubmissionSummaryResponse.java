package com.codearena.submission.dto;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.Submission;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A submission as it appears in a history list.
 *
 * <p>A closed record with <strong>no source code field</strong>. That is both a bandwidth
 * decision — a page of twenty submissions would otherwise carry a few hundred kilobytes of
 * text nobody reads in a table — and a containment one: the type physically cannot carry a
 * program, so no change to the listing query can start leaking one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One row of a submission history. Source code is only available from the detail endpoint.")
public record SubmissionSummaryResponse(
        UUID id,
        UUID problemId,
        String problemSlug,
        String problemTitle,
        Language language,
        SubmissionStatus status,
        Integer testsTotal,
        Integer testsPassed,
        Integer runtimeMs,
        Integer memoryKb,
        Instant createdAt,
        Instant finishedAt) {

    public static SubmissionSummaryResponse from(Submission submission) {
        return new SubmissionSummaryResponse(
                submission.getPublicId(),
                submission.getProblem().getPublicId(),
                submission.getProblem().getSlug(),
                submission.getProblem().getTitle(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getTestsTotal(),
                submission.getTestsPassed(),
                submission.getRuntimeMs(),
                submission.getMemoryKb(),
                submission.getCreatedAt(),
                submission.getFinishedAt());
    }
}
