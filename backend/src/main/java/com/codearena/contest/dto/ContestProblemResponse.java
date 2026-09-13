package com.codearena.contest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One problem inside a contest, as a contestant sees it.
 *
 * <p>Enough to navigate and to know what it is worth. The statement, examples and
 * constraints come from the existing problem endpoint, which already knows how to serve a
 * problem without disclosing its test cases — there is no second path to that data here.
 */
@Schema(description = "A problem within a contest.")
public record ContestProblemResponse(
        @Schema(description = "The problem's public id, as used by the problem endpoints.")
        UUID problemId,
        String problemSlug,
        String title,
        @Schema(description = "Contest label: A, B, C...", example = "A")
        String label,
        int displayOrder,
        int points,
        @Schema(description = "Whether the caller has solved it in this contest. Null when anonymous.")
        Boolean solved,
        @Schema(description = "The caller's counted attempts on it in this contest.")
        Integer attempts) {
}
