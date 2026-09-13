package com.codearena.contest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Creating or updating a contest.
 *
 * <p>A closed record with five fields. There is deliberately no {@code status},
 * {@code lifecycle}, {@code createdBy} or {@code id} — those are decided by the server, and a
 * field that does not exist on the type cannot be overposted into one that does.
 *
 * <p>Timestamps are {@link Instant}, so the wire format is ISO-8601 with an offset and the
 * server never has to guess a zone. {@code 2026-03-01T09:00:00Z} and
 * {@code 2026-03-01T14:30:00+05:30} are the same instant and are treated as such.
 */
public record ContestRequest(

        @Schema(example = "Spring Contest 2026")
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be at most 200 characters")
        String title,

        @Schema(description = "Lowercase URL identifier. Must be unique.", example = "spring-2026")
        @NotBlank(message = "Slug is required")
        @Size(max = 200, message = "Slug must be at most 200 characters")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                 message = "Slug must be lowercase words separated by single hyphens")
        String slug,

        @Size(max = 20000, message = "Description must be at most 20000 characters")
        String description,

        @Schema(description = "Start instant, ISO-8601 with offset. Stored in UTC.",
                example = "2026-03-01T09:00:00Z")
        @NotNull(message = "A start time is required")
        Instant startAt,

        @Schema(description = "End instant, exclusive: a contest is over at exactly this moment.",
                example = "2026-03-01T12:00:00Z")
        @NotNull(message = "An end time is required")
        Instant endAt) {
}
