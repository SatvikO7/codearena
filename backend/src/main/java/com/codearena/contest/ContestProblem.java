package com.codearena.contest;

import com.codearena.problem.Problem;
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
 * One problem's place in one contest.
 *
 * <p>A <b>reference</b> to a {@link Problem}, never a copy of one. The statement, examples
 * and test cases stay where they are; this adds only what is true of the problem within this
 * contest: where it appears, what it is worth, and the letter contestants call it by.
 *
 * <p>Copying the problem would create a second version of the answer key to keep in step,
 * and a correction to a statement would never reach the contest using it.
 */
@Entity
@Table(name = "contest_problems")
public class ContestProblem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false, updatable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false, updatable = false)
    private Problem problem;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ContestProblem() {
        // Required by JPA.
    }

    public static ContestProblem of(Contest contest, Problem problem, int displayOrder, int points) {
        ContestProblem entry = new ContestProblem();
        entry.contest = contest;
        entry.problem = problem;
        entry.displayOrder = displayOrder;
        entry.points = points;
        return entry;
    }

    /**
     * The contestant-facing label: A, B, C, …
     *
     * <p>Derived from the position rather than stored, so it cannot drift out of step with
     * the ordering. Past Z it continues AA, AB — a contest that long is unlikely, but
     * wrapping round to A silently would be worse than an odd-looking label.
     */
    public String label() {
        StringBuilder label = new StringBuilder();
        int index = displayOrder;
        do {
            label.insert(0, (char) ('A' + (index % 26)));
            index = index / 26 - 1;
        } while (index >= 0);
        return label.toString();
    }

    public void moveTo(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void repoint(int points) {
        this.points = points;
    }

    public Long getId() {
        return id;
    }

    public Contest getContest() {
        return contest;
    }

    public Problem getProblem() {
        return problem;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public int getPoints() {
        return points;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
