package com.codearena.rating;

import com.codearena.contest.Contest;
import com.codearena.user.User;
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
 * One competitor's current standing.
 *
 * <p>A row exists only once a user has been rated at least once. That is deliberate: it makes
 * "unrated" a real state rather than a default value indistinguishable from somebody who has
 * competed and happens to sit at 1500. The global ranking reads this table, so an account
 * that has never entered a rated contest does not appear in it — which is the correct
 * behaviour and falls out of the model rather than needing a filter.
 *
 * <h2>This is a cache, and the history is the truth</h2>
 * {@code rating} is a fold over {@link ContestRatingChange} rows. Keeping it here means the
 * leaderboard is an indexed scan rather than an aggregation over every rating change ever
 * recorded, but the derivation is the authority: an invariant test asserts that every user's
 * rating equals the {@code ratingAfter} of their most recent change.
 */
@Entity
@Table(name = "user_ratings")
public class UserRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "rating", nullable = false)
    private int rating;

    /** The highest rating ever held. Never decreases. */
    @Column(name = "peak_rating", nullable = false)
    private int peakRating;

    @Column(name = "contests_rated", nullable = false)
    private int contestsRated;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_rated_contest_id")
    private Contest lastRatedContest;

    @Column(name = "last_rated_at")
    private Instant lastRatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserRating() {
    }

    /**
     * A competitor's first rated contest.
     *
     * <p>Starts at {@link RatingCalculator#INITIAL_RATING} with zero contests behind them, so
     * the change about to be applied is computed against the same rating the calculator was
     * given. The two must agree or the stored history would not explain the stored rating.
     */
    public static UserRating starting(User user, Instant now) {
        UserRating rating = new UserRating();
        rating.user = user;
        rating.rating = RatingCalculator.INITIAL_RATING;
        rating.peakRating = RatingCalculator.INITIAL_RATING;
        rating.contestsRated = 0;
        rating.createdAt = now;
        rating.updatedAt = now;
        return rating;
    }

    /**
     * Applies one contest's result.
     *
     * <p>The only way this entity changes. There is no setter for {@code rating}, so a rating
     * cannot move without a contest to account for it — which is the same principle as the
     * rating history being append-only, enforced one layer up.
     *
     * @param newRating the rating after the change, as the calculator computed it
     */
    void applyContestResult(int newRating, Contest contest, Instant now) {
        this.rating = newRating;
        this.peakRating = Math.max(this.peakRating, newRating);
        this.contestsRated += 1;
        this.lastRatedContest = contest;
        this.lastRatedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public int getRating() {
        return rating;
    }

    public int getPeakRating() {
        return peakRating;
    }

    public int getContestsRated() {
        return contestsRated;
    }

    public Contest getLastRatedContest() {
        return lastRatedContest;
    }

    public Instant getLastRatedAt() {
        return lastRatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
