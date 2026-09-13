package com.codearena.contest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Adding a published problem to a contest. */
public record ContestProblemRequest(

        @Schema(description = "The problem's public id. It must already be PUBLISHED.")
        @NotNull(message = "A problem id is required")
        UUID problemId,

        @Schema(description = "What solving it is worth. Omitted defaults to 100.", example = "100")
        @Min(value = 1, message = "Points must be at least 1")
        @Max(value = 10000, message = "Points must be at most 10000")
        Integer points) {

    public int pointsOrDefault() {
        return points == null ? 100 : points;
    }
}
