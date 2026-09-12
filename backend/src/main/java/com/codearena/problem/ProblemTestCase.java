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

import java.time.Instant;

/**
 * A judge test case.
 *
 * <p>This is the most security-sensitive data in the phase. A hidden case's
 * {@link #expectedOutput} is the answer key: leaking it would let anyone hard-code their
 * way to an ACCEPTED verdict once judging exists. No user-facing service reads this
 * entity — only the admin API, and from Phase 7 the judge worker.
 *
 * <p>Nothing here executes anything. These rows are authoring data an administrator
 * enters while writing the problem; the execution engine that consumes them is Phase 6.
 */
@Entity
@Table(name = "problem_test_cases")
public class ProblemTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "input", nullable = false, columnDefinition = "text")
    private String input;

    @Column(name = "expected_output", nullable = false, columnDefinition = "text")
    private String expectedOutput;

    /**
     * Defaults to hidden, both here and in the schema. A test case that is secret only
     * because somebody remembered to set a flag is a test case that will eventually be
     * published by accident.
     */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = true;

    /** Relative contribution to a partial score. Unused until judging exists. */
    @Column(name = "weight", nullable = false)
    private int weight = 1;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProblemTestCase() {
        // Required by JPA.
    }

    ProblemTestCase(Problem problem, int position, String input, String expectedOutput,
                    boolean hidden, int weight) {
        this.problem = problem;
        this.position = position;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.hidden = hidden;
        this.weight = weight;
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

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public boolean isHidden() {
        return hidden;
    }

    public int getWeight() {
        return weight;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Deliberately excludes input and expected output so neither can reach a log line. */
    @Override
    public String toString() {
        return "ProblemTestCase{id=%d, position=%d, hidden=%s, weight=%d}"
                .formatted(id, position, hidden, weight);
    }
}
