package com.codearena.rating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One rating movement, recorded permanently.
 *
 * <p>This is the record that explains why somebody's rating is what it is. A system that can
 * quietly rewrite it cannot be used to settle a disagreement, which is most of what it is
 * for — so it is append-only, enforced three times over in the same way the audit log is
 * (ADR-037):
 *
 * <ol>
 *   <li>The entity is {@link Immutable} and has no setters, so Hibernate will not issue an
 *       UPDATE for it.</li>
 *   <li>No repository method and no endpoint modifies or removes one. There is no
 *       {@code PUT /rating-history/{id}} and there will not be.</li>
 *   <li><b>A database trigger refuses UPDATE and DELETE outright</b> (V9). "The code does not
 *       do that" is a weaker guarantee than "the database refuses".</li>
 * </ol>
 *
 * <h2>Why the contest result is copied rather than joined</h2>
 * {@code rank}, {@code score} and {@code penalty} are stored here even though the standings
 * can be recomputed from submissions at any time. That is the point: standings are a live
 * derivation, and a later correction to a submission must not silently rewrite the inputs of
 * a rating change that was applied months ago. These columns say what the rating was actually
 * based on.
 *
 * <p>{@code expectedScore}, {@code actualScore} and {@code kFactor} are kept for the same
 * reason — together with the two ratings they make the arithmetic reproducible, so "why did I
 * lose 14 points" has an answer that does not require re-running the contest.
 *
 * <h2>Identifiers, not associations</h2>
 * {@code userId} and {@code contestId} are plain values with no foreign key, matching
 * {@code audit_events}. A foreign key has to do something when its target disappears, and
 * both options are wrong here: CASCADE erases exactly the history worth keeping, and SET NULL
 * is an UPDATE, which the append-only trigger refuses.
 */
@Entity
@Immutable
@Table(name = "contest_rating_changes")
public class ContestRatingChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "contest_id", nullable = false, updatable = false)
    private Long contestId;

    @Column(name = "rating_before", nullable = false, updatable = false)
    private int ratingBefore;

    @Column(name = "rating_after", nullable = false, updatable = false)
    private int ratingAfter;

    @Column(name = "rating_change", nullable = false, updatable = false)
    private int ratingChange;

    @Column(name = "rank", nullable = false, updatable = false)
    private int rank;

    @Column(name = "participant_count", nullable = false, updatable = false)
    private int participantCount;

    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Column(name = "penalty", nullable = false, updatable = false)
    private int penalty;

    @Column(name = "expected_score", nullable = false, updatable = false)
    private double expectedScore;

    @Column(name = "actual_score", nullable = false, updatable = false)
    private double actualScore;

    @Column(name = "k_factor", nullable = false, updatable = false)
    private int kFactor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContestRatingChange() {
    }

    ContestRatingChange(long userId, long contestId, RatingCalculator.Change change,
                        int participantCount, Instant now) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.contestId = contestId;
        this.ratingBefore = change.ratingBefore();
        this.ratingAfter = change.ratingAfter();
        this.ratingChange = change.ratingChange();
        this.rank = change.rank();
        this.participantCount = participantCount;
        this.score = change.score();
        this.penalty = change.penalty();
        this.expectedScore = change.expectedScore();
        this.actualScore = change.actualScore();
        this.kFactor = change.kFactor();
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getContestId() {
        return contestId;
    }

    public int getRatingBefore() {
        return ratingBefore;
    }

    public int getRatingAfter() {
        return ratingAfter;
    }

    public int getRatingChange() {
        return ratingChange;
    }

    public int getRank() {
        return rank;
    }

    public int getParticipantCount() {
        return participantCount;
    }

    public int getScore() {
        return score;
    }

    public int getPenalty() {
        return penalty;
    }

    public double getExpectedScore() {
        return expectedScore;
    }

    public double getActualScore() {
        return actualScore;
    }

    public int getKFactor() {
        return kFactor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Never prints anything that is not already public. */
    @Override
    public String toString() {
        return "ContestRatingChange{contest=%d, change=%+d, rank=%d}"
                .formatted(contestId, ratingChange, rank);
    }
}
