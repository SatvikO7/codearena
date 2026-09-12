package com.codearena.problem;

/**
 * The fixed vocabulary of topic tags.
 *
 * <p>A constrained set rather than free text. Free-text tags drift into {@code dp},
 * {@code DP}, {@code dynamic-programming} and {@code dynamicProgramming} all describing
 * one concept, at which point filtering by tag stops being useful. Adding a tag is an
 * enum constant plus a value in the {@code ck_problem_tags_tag} check constraint.
 */
public enum ProblemTag {
    ARRAY,
    STRING,
    HASHING,
    SORTING,
    BINARY_SEARCH,
    TWO_POINTERS,
    STACK,
    QUEUE,
    LINKED_LIST,
    TREE,
    GRAPH,
    GREEDY,
    DYNAMIC_PROGRAMMING,
    DSU,
    MATH,
    BIT_MANIPULATION,
    HEAP,
    RECURSION
}
