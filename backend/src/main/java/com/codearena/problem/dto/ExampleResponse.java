package com.codearena.problem.dto;

import com.codearena.problem.ProblemExample;

/** A worked example, safe to show anyone who can see the problem. */
public record ExampleResponse(int position, String input, String output, String explanation) {

    public static ExampleResponse from(ProblemExample example) {
        return new ExampleResponse(
                example.getPosition(), example.getInput(), example.getOutput(), example.getExplanation());
    }
}
