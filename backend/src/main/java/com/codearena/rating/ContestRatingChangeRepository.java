package com.codearena.rating;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of the rating history.
 *
 * <p>Reads and one insert, and nothing else. There is deliberately no update or delete method
 * here — not because the trigger would refuse them, but because a repository that offered
 * them would be describing a capability the system does not have.
 */
public interface ContestRatingChangeRepository extends JpaRepository<ContestRatingChange, Long> {

    /** Whether this contest has already produced rating changes. */
    boolean existsByContestId(Long contestId);

    long countByContestId(Long contestId);

    long countByUserId(Long userId);

    /** One competitor's movement in one contest, for the contest results page. */
    Optional<ContestRatingChange> findByContestIdAndUserId(Long contestId, Long userId);

    /**
     * One competitor's history, newest first.
     *
     * <p>Joined to contests here rather than in the caller so the profile page is one query
     * rather than one per contest. Driven by {@code ix_rating_changes_user_time}.
     */
    interface HistoryRow {
        UUID getContestId();

        String getContestTitle();

        String getContestSlug();

        Instant getContestEndAt();

        int getRatingBefore();

        int getRatingAfter();

        int getRatingChange();

        int getRank();

        int getParticipantCount();

        int getScore();

        int getPenalty();

        Instant getCreatedAt();
    }

    @Query(value = """
            SELECT c.public_id     AS contestId,
                   c.title         AS contestTitle,
                   c.slug          AS contestSlug,
                   c.end_at        AS contestEndAt,
                   r.rating_before AS ratingBefore,
                   r.rating_after  AS ratingAfter,
                   r.rating_change AS ratingChange,
                   r.rank          AS rank,
                   r.participant_count AS participantCount,
                   r.score         AS score,
                   r.penalty       AS penalty,
                   r.created_at    AS createdAt
            FROM contest_rating_changes r
            JOIN contests c ON c.id = r.contest_id
            WHERE r.user_id = :userId
            ORDER BY r.created_at DESC, r.id DESC
            """,
            countQuery = "SELECT count(*) FROM contest_rating_changes WHERE user_id = :userId",
            nativeQuery = true)
    List<HistoryRow> findHistory(@Param("userId") long userId, Pageable pageable);

    /**
     * The rating progression, oldest first, for the graph.
     *
     * <p>Separate from {@link #findHistory} because a graph wants every point in chronological
     * order while a list wants a page of the most recent — different questions, and merging
     * them would mean the graph paginating, which is not a thing a graph does.
     */
    interface ProgressionPoint {
        Instant getAt();

        int getRating();

        String getContestTitle();
    }

    @Query(value = """
            SELECT r.created_at   AS at,
                   r.rating_after AS rating,
                   c.title        AS contestTitle
            FROM contest_rating_changes r
            JOIN contests c ON c.id = r.contest_id
            WHERE r.user_id = :userId
            ORDER BY r.created_at ASC, r.id ASC
            """, nativeQuery = true)
    List<ProgressionPoint> findProgression(@Param("userId") long userId);
}
