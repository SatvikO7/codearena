package com.codearena.problem.dto;

import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemTag;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A published problem as a solver sees it.
 *
 * <p>This record is the user-facing security boundary of Phase 3, and it is closed: there
 * is no field for test cases, so leaking one would require somebody to add it here
 * deliberately rather than merely forget to strip it. The examples it does carry are
 * public by definition.
 *
 * <p>The execution limits are included because a solver needs them to choose an
 * algorithm. Nothing enforces them yet; that is Phase 6.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetailResponse(
        UUID id,
        String slug,
        String title,
        String statement,
        String inputFormat,
        String outputFormat,
        String constraints,
        String explanation,
        Difficulty difficulty,
        Set<ProblemTag> tags,
        int timeLimitMs,
        int memoryLimitMb,
        List<ExampleResponse> examples,
        Instant createdAt) {

    public static ProblemDetailResponse from(Problem problem) {
        return new ProblemDetailResponse(
                problem.getPublicId(),
                problem.getSlug(),
                problem.getTitle(),
                problem.getStatement(),
                problem.getInputFormat(),
                problem.getOutputFormat(),
                problem.getConstraints(),
                problem.getExplanation(),
                problem.getDifficulty(),
                new TreeSet<>(problem.getTags()),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb(),
                problem.getExamples().stream().map(ExampleResponse::from).toList(),
                problem.getCreatedAt());
    }
}
