package com.codearena.problem.dto;

import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemStatus;
import com.codearena.problem.ProblemTag;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A problem as it appears in a listing.
 *
 * <p>Carries only what a browse view renders. The statement, formats, examples and test
 * cases are all absent — a catalogue page has no use for them, and shipping them would
 * mean sending kilobytes per row and widening the surface for a leak.
 *
 * <p>{@code status} is populated for administrative listings and omitted entirely from
 * the public catalogue, where every row is PUBLISHED by construction.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemSummaryResponse(
        UUID id,
        String slug,
        String title,
        Difficulty difficulty,
        Set<ProblemTag> tags,
        ProblemStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** The public catalogue view: no status, because everything listed is published. */
    public static ProblemSummaryResponse forCatalogue(Problem problem) {
        return new ProblemSummaryResponse(
                problem.getPublicId(), problem.getSlug(), problem.getTitle(), problem.getDifficulty(),
                sortedTags(problem), null, problem.getCreatedAt(), null);
    }

    /** The administrative view, which shows the lifecycle state. */
    public static ProblemSummaryResponse forAdmin(Problem problem) {
        return new ProblemSummaryResponse(
                problem.getPublicId(), problem.getSlug(), problem.getTitle(), problem.getDifficulty(),
                sortedTags(problem), problem.getStatus(), problem.getCreatedAt(), problem.getUpdatedAt());
    }

    /** Sorted so the rendered order is stable rather than whatever the set iterates in. */
    private static Set<ProblemTag> sortedTags(Problem problem) {
        return new TreeSet<>(problem.getTags());
    }
}
