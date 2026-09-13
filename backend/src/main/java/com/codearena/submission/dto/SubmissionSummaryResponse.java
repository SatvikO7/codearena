package com.codearena.submission.dto;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.SubmissionSummaryProjection;
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
 *
 * <p>Built from {@link SubmissionSummaryProjection}, not from an entity, so the source is
 * never read out of the database in the first place.
 *
 * <p>Memory is deliberately absent. It is <em>enforced</em> — a program exceeding its
 * ceiling is killed by the kernel and reported as MEMORY_LIMIT_EXCEEDED — but peak usage is
 * not <em>measured</em>, so there is no honest number to put here. See the known
 * limitations in the README.
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
        Instant createdAt,
        Instant finishedAt) {

    public static SubmissionSummaryResponse from(SubmissionSummaryProjection projection) {
        return new SubmissionSummaryResponse(
                projection.publicId(),
                projection.problemPublicId(),
                projection.problemSlug(),
                projection.problemTitle(),
                projection.language(),
                projection.status(),
                projection.testsTotal(),
                projection.testsPassed(),
                projection.runtimeMs(),
                projection.createdAt(),
                projection.finishedAt());
    }
}
