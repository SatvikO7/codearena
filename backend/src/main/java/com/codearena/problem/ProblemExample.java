package com.codearena.problem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A worked example shown on the problem page.
 *
 * <p>Examples are public by definition — one exists in order to be read. Anything that
 * must stay secret is a {@link ProblemTestCase}, not an example. Keeping the two as
 * distinct types rather than one table with a flag means a user-facing query cannot
 * accidentally return a secret row by forgetting a predicate.
 */
@Entity
@Table(name = "problem_examples")
public class ProblemExample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** Zero-based display order, unique per problem so ordering is total and stable. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "input", nullable = false, columnDefinition = "text")
    private String input;

    @Column(name = "output", nullable = false, columnDefinition = "text")
    private String output;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    protected ProblemExample() {
        // Required by JPA.
    }

    ProblemExample(Problem problem, int position, String input, String output, String explanation) {
        this.problem = problem;
        this.position = position;
        this.input = input;
        this.output = output;
        this.explanation = explanation;
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public String getExplanation() {
        return explanation;
    }
}
