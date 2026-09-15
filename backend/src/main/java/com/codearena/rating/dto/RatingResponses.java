package com.codearena.rating.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the rating API returns.
 *
 * <p>Gathered into one file because they are small, closely related and read together. What
 * matters more than the shape is what is <b>absent</b>: no email, no role, no internal
 * identifier, no account state. The leaderboard is the most public surface this system has,
 * and it is built from a table that does not contain anything private to begin with.
 */
public final class RatingResponses {

    private RatingResponses() {
    }

    @Schema(description = "One row of the global ranking.")
    public record RankingEntry(
            @Schema(description = "Competition rank: ties share a rank and the next rank skips.")
            long rank,
            UUID userId,
            String username,
            int rating,
            int peakRating,
            int contestsRated) {
    }

    @Schema(description = "A competitor's current standing.")
    public record RatingProfile(
            UUID userId,
            String username,

            @Schema(description = "Null when this competitor has never been rated. Not zero — "
                                + "an unrated competitor has no rating, which is different "
                                + "from having a rating of nothing.")
            Integer rating,

            Integer peakRating,

            @Schema(description = "Global rank, or null when unrated.")
            Long rank,

            int contestsRated,

            @Schema(description = "Whether this competitor has completed a rated contest.")
            boolean rated,

            Instant lastRatedAt,

            @Schema(description = "Most recent results, newest first.")
            List<HistoryEntry> recent,

            @Schema(description = "Rating after each rated contest, oldest first, for a graph.")
            List<ProgressionPoint> progression) {
    }

    @Schema(description = "One rated contest in a competitor's history.")
    public record HistoryEntry(
            UUID contestId,
            String contestTitle,
            String contestSlug,
            Instant contestEndAt,
            int rank,
            int participantCount,
            int score,
            int penalty,
            int ratingBefore,
            int ratingChange,
            int ratingAfter,
            Instant ratedAt) {
    }

    @Schema(description = "One point on the rating graph.")
    public record ProgressionPoint(Instant at, int rating, String contestTitle) {
    }

    /**
     * A competitor's rating outcome for one contest.
     *
     * <p>Every field after {@code status} is null unless the contest is both rated and
     * finalised. That is deliberate: a contest that has ended but not yet been rated must not
     * report a change of zero, because zero is a real rating change and "not yet" is not.
     */
    @Schema(description = "The rating outcome of one contest for one competitor.")
    public record ContestRatingResult(
            UUID contestId,

            @Schema(description = "UNRATED, PENDING, FINALIZED or CANCELLED.")
            String status,

            @Schema(description = "Null unless the contest is rated and finalised.")
            Integer rank,
            Integer participantCount,
            Integer score,
            Integer penalty,
            Integer ratingBefore,
            Integer ratingChange,
            Integer ratingAfter,
            Instant finalizedAt) {

        /** The contest does not move ratings, and never will. */
        public static ContestRatingResult unrated(UUID contestId) {
            return new ContestRatingResult(contestId, "UNRATED",
                    null, null, null, null, null, null, null, null);
        }

        /** The contest is rated and has not been finalised yet. Ratings are coming. */
        public static ContestRatingResult pending(UUID contestId) {
            return new ContestRatingResult(contestId, "PENDING",
                    null, null, null, null, null, null, null, null);
        }

        /** The contest was called off. There will never be a rating. */
        public static ContestRatingResult cancelled(UUID contestId) {
            return new ContestRatingResult(contestId, "CANCELLED",
                    null, null, null, null, null, null, null, null);
        }

        /**
         * The contest was rated and finalised, and this competitor was not in the field.
         *
         * <p>Distinguished from PENDING because the answers differ: one means "wait", the
         * other means "you did not compete in this".
         */
        public static ContestRatingResult notParticipated(UUID contestId, Instant finalizedAt) {
            return new ContestRatingResult(contestId, "FINALIZED",
                    null, null, null, null, null, null, null, finalizedAt);
        }
    }

    @Schema(description = "The result of an administrative finalisation.")
    public record FinalizationResult(
            UUID contestId,

            @Schema(description = "True when this call found the work already done. Not an "
                                + "error: finalisation is idempotent.")
            boolean alreadyFinalized,

            boolean rated,
            int ratedParticipants,
            Instant finalizedAt) {
    }
}
