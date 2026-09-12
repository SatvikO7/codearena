package com.codearena.problem.dto;

import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemStatus;
import com.codearena.problem.ProblemTag;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The authoring view of a problem: everything, test cases included.
 *
 * <p>Deliberately a separate type from {@link ProblemDetailResponse} rather than the same
 * record with fields blanked out. Two distinct shapes mean the user-facing response
 * cannot carry an answer key even by mistake, because it has nowhere to put one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminProblemDetailResponse(
        UUID id,
        String slug,
        String title,
        String statement,
        String inputFormat,
        String outputFormat,
        String constraints,
        String explanation,
        Difficulty difficulty,
        ProblemStatus status,
        Set<ProblemTag> tags,
        int timeLimitMs,
        int memoryLimitMb,
        List<ExampleResponse> examples,
        List<TestCaseResponse> testCases,
        List<String> missingForPublication,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminProblemDetailResponse from(Problem problem) {
        return new AdminProblemDetailResponse(
                problem.getPublicId(),
                problem.getSlug(),
                problem.getTitle(),
                problem.getStatement(),
                problem.getInputFormat(),
                problem.getOutputFormat(),
                problem.getConstraints(),
                problem.getExplanation(),
                problem.getDifficulty(),
                problem.getStatus(),
                new TreeSet<>(problem.getTags()),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb(),
                problem.getExamples().stream().map(ExampleResponse::from).toList(),
                problem.getTestCases().stream().map(TestCaseResponse::from).toList(),
                // Surfaced so the authoring UI can show what still blocks publication
                // instead of making the admin discover it by pressing Publish.
                problem.missingForPublication(),
                problem.getCreatedBy() == null ? null : problem.getCreatedBy().getUsername(),
                problem.getUpdatedBy() == null ? null : problem.getUpdatedBy().getUsername(),
                problem.getCreatedAt(),
                problem.getUpdatedAt());
    }
}
