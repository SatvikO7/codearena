package com.codearena.submission;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The columns a history row actually needs, selected directly from the database.
 *
 * <p>A constructor projection rather than an entity. The distinction matters: loading
 * {@link Submission} entities for a listing would read every column including
 * {@code source_code}, so a page of twenty submissions would pull the full text of twenty
 * programs out of PostgreSQL in order to render a table that shows none of them.
 *
 * <p>There is no source-code field here, so the history endpoint cannot return one even by
 * mistake — the same containment argument as the response DTO, one layer earlier.
 */
public record SubmissionSummaryProjection(
        UUID publicId,
        UUID problemPublicId,
        String problemSlug,
        String problemTitle,
        Language language,
        SubmissionStatus status,
        Integer testsTotal,
        Integer testsPassed,
        Integer runtimeMs,
        Instant createdAt,
        Instant finishedAt) {
}
