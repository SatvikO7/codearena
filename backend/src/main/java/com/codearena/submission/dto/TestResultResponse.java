package com.codearena.submission.dto;

import com.codearena.submission.SubmissionTestResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The outcome of one test, as its submitter sees it.
 *
 * <p>A number, a pass or fail, and a duration. There is no field for input, expected output
 * or actual output — not filtered out, absent — so nothing that reads this type can disclose
 * what a hidden test contains.
 *
 * <p>{@code hidden} is reported so the UI can say "Hidden test 3" rather than pretending
 * every test is public. Knowing that test 3 is secret reveals nothing about it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One test's outcome. Never includes the test's input or expected output.")
public record TestResultResponse(

        @Schema(description = "Zero-based test index, matching the problem's test ordering.")
        int position,

        boolean passed,

        @Schema(description = "Wall-clock time for this test, or null if it never ran. "
                            + "Judging stops at the first failure, so later tests have no time.")
        Integer runtimeMs,

        @Schema(description = "Whether this test is hidden from the problem statement.")
        boolean hidden) {

    public static TestResultResponse from(SubmissionTestResult result) {
        return new TestResultResponse(
                result.getPosition(), result.isPassed(), result.getRuntimeMs(), result.isHidden());
    }
}
