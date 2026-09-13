package com.codearena.contest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Changing a problem's points or position within a contest.
 *
 * <p>Both fields are optional and omitted means unchanged, matching how problem updates
 * behave elsewhere (ADR-017's sibling rule): a client that wants to change the points should
 * not have to resend the ordering and risk clobbering a concurrent reorder.
 */
public record ContestProblemUpdateRequest(

        @Min(value = 1, message = "Points must be at least 1")
        @Max(value = 10000, message = "Points must be at most 10000")
        Integer points,

        @Min(value = 0, message = "Display order must be zero or greater")
        Integer displayOrder) {
}
