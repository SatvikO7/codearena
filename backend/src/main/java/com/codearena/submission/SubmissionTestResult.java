package com.codearena.submission;

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
 * The outcome of one test case for one submission.
 *
 * <p>There is no field for input, expected output or actual output, and that is the point:
 * the type cannot carry hidden test data, so no projection or future edit can leak it.
 * {@link #hidden} records whether the test was secret <em>at judging time</em>, so that an
 * administrator later making a test public does not retroactively change what an old
 * submission is allowed to reveal.
 */
@Entity
@Table(name = "submission_test_results")
public class SubmissionTestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    /** Null when the test never ran, which is every test after the first failure. */
    @Column(name = "runtime_ms")
    private Integer runtimeMs;

    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected SubmissionTestResult() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public boolean isPassed() {
        return passed;
    }

    public Integer getRuntimeMs() {
        return runtimeMs;
    }

    public boolean isHidden() {
        return hidden;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
