package com.codearena.problem.dto;

import com.codearena.problem.ProblemTestCase;

/**
 * A judge test case, including its expected output.
 *
 * <p>Returned exclusively by the admin API. This type must never appear in a response
 * built for a normal user: for a hidden case the expected output is the answer key, and
 * publishing it would make an ACCEPTED verdict trivially forgeable once judging exists.
 */
public record TestCaseResponse(
        int position,
        String input,
        String expectedOutput,
        boolean hidden,
        int weight) {

    public static TestCaseResponse from(ProblemTestCase testCase) {
        return new TestCaseResponse(
                testCase.getPosition(), testCase.getInput(), testCase.getExpectedOutput(),
                testCase.isHidden(), testCase.getWeight());
    }
}
