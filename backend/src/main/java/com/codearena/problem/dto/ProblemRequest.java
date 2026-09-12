package com.codearena.problem.dto;

import com.codearena.problem.Difficulty;
import com.codearena.problem.ProblemTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * The payload for creating or updating a problem.
 *
 * <p>There is deliberately <strong>no status field</strong>. Lifecycle changes happen
 * only through the explicit publish, unpublish and archive endpoints, each of which
 * enforces the transition rules and the completeness checks. If status were settable
 * here, a client could send {@code "status": "PUBLISHED"} and put an empty problem into
 * the public catalogue — the classic mass-assignment hole.
 *
 * <p>For the same reason there is no id, publicId, createdBy, createdAt or updatedAt:
 * identity and provenance are the server's to decide, and a field that does not exist on
 * the request type cannot be overposted.
 *
 * <p>Only {@code title} and {@code difficulty} are mandatory. The rest may be filled in
 * over several edits, so an author can save a rough draft; publication is where
 * completeness is enforced.
 *
 * <p>On update, a field left out of the payload is left unchanged; an explicitly empty
 * value ({@code ""} or {@code []}) clears it. See {@code ProblemAdminService.applyContent}
 * for why omission does not mean deletion.
 */
public record ProblemRequest(

        @Schema(example = "Two Sum", description = "Display title.")
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be at most 200 characters")
        String title,

        @Schema(example = "two-sum",
                description = "URL handle. Derived from the title when omitted on create. "
                            + "On update, omitting it leaves the existing slug unchanged.")
        @Size(max = 120, message = "Slug must be at most 120 characters")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                 message = "Slug must be lower-case words separated by single hyphens")
        String slug,

        @Size(max = 20000, message = "Statement must be at most 20000 characters")
        String statement,

        @Size(max = 4000, message = "Input format must be at most 4000 characters")
        String inputFormat,

        @Size(max = 4000, message = "Output format must be at most 4000 characters")
        String outputFormat,

        @Size(max = 4000, message = "Constraints must be at most 4000 characters")
        String constraints,

        @Size(max = 8000, message = "Explanation must be at most 8000 characters")
        String explanation,

        @NotNull(message = "Difficulty is required")
        Difficulty difficulty,

        Set<ProblemTag> tags,

        @Schema(description = "Wall-clock budget per test case, in milliseconds. Not enforced until Phase 6.")
        @Min(value = 100, message = "Time limit must be at least 100 ms")
        @Max(value = 15000, message = "Time limit must be at most 15000 ms")
        Integer timeLimitMs,

        @Schema(description = "Memory budget per test case, in megabytes. Not enforced until Phase 6.")
        @Min(value = 16, message = "Memory limit must be at least 16 MB")
        @Max(value = 1024, message = "Memory limit must be at most 1024 MB")
        Integer memoryLimitMb,

        @Schema(description = "Replaces every example. Order is preserved as sent.")
        @Valid
        @Size(max = 10, message = "A problem may have at most 10 examples")
        List<ExampleRequest> examples,

        @Schema(description = "Replaces every test case. Order is preserved as sent. Admin-only data.")
        @Valid
        @Size(max = 200, message = "A problem may have at most 200 test cases")
        List<TestCaseRequest> testCases) {

    /** A worked example. Always public. */
    public record ExampleRequest(
            @NotNull(message = "Example input is required")
            @Size(max = 4000, message = "Example input must be at most 4000 characters")
            String input,

            @NotNull(message = "Example output is required")
            @Size(max = 4000, message = "Example output must be at most 4000 characters")
            String output,

            @Size(max = 2000, message = "Example explanation must be at most 2000 characters")
            String explanation) {
    }

    /** A judge test case. Hidden unless explicitly marked otherwise. */
    public record TestCaseRequest(
            @NotNull(message = "Test case input is required")
            @Size(max = 100000, message = "Test case input must be at most 100000 characters")
            String input,

            @NotNull(message = "Test case expected output is required")
            @Size(max = 100000, message = "Test case expected output must be at most 100000 characters")
            String expectedOutput,

            @Schema(description = "Defaults to true when omitted: a test case is secret unless "
                                + "someone deliberately says otherwise.")
            Boolean hidden,

            @Min(value = 1, message = "Weight must be at least 1")
            @Max(value = 100, message = "Weight must be at most 100")
            Integer weight) {

        /** Absent means hidden. Failing safe is the only correct default for an answer key. */
        public boolean hiddenOrDefault() {
            return hidden == null || hidden;
        }

        public int weightOrDefault() {
            return weight == null ? 1 : weight;
        }
    }
}
