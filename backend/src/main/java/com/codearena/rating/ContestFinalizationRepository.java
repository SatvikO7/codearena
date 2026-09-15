package com.codearena.rating;

import com.codearena.contest.Contest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The finalisation claim, and finding work that still needs it.
 *
 * <h2>Why the claim is SQL and not an entity write</h2>
 * Two processes can try to finalise the same contest at the same moment — an administrator's
 * retry, the scheduled sweeper, and a second application instance are three realistic ways in.
 * Deciding in Java means read, check, write, and <b>two transactions can both read "not
 * finalised"</b>. Both would then rate the contest, and every participant would receive their
 * change twice.
 *
 * <p>A conditional UPDATE has no such window. PostgreSQL serialises concurrent updates to a
 * row, so of several callers running
 *
 * <pre>
 *   UPDATE contests SET rating_finalized_at = :now
 *    WHERE id = :id AND rating_finalized_at IS NULL
 * </pre>
 *
 * exactly one updates a row and the rest update none. The return value <em>is</em> the
 * decision: 1 means "you finalise it", 0 means "somebody else already did". This is the same
 * mechanism the judge uses to claim a submission (ADR-018), for the same reason.
 *
 * <p>A unique constraint on {@code (contest_id, user_id)} in the history table sits behind it
 * as a second line: even if the claim were somehow won twice, a double rating would be
 * physically impossible rather than merely unlikely.
 */
public interface ContestFinalizationRepository extends JpaRepository<Contest, Long> {

    /**
     * Claims a contest for finalisation.
     *
     * <p>{@code rated_participant_count} is set to 0 here and corrected at the end of the
     * transaction, because the check constraint requires the two fields to move together and
     * the real count is not known until the standings have been computed.
     *
     * @return 1 if this caller won the claim, 0 if it was already claimed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE contests
               SET rating_finalized_at = :now,
                   rated_participant_count = 0,
                   updated_at = :now
             WHERE id = :contestId
               AND rating_finalized_at IS NULL
            """, nativeQuery = true)
    int claimForFinalization(@Param("contestId") long contestId, @Param("now") Instant now);

    /**
     * Records the outcome once the rating changes are written.
     *
     * <p>Separate from the claim so the claim can be as narrow as possible: the whole point of
     * it is that it is one indivisible decision, and folding a count into it would mean
     * knowing the count before deciding who computes it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE contests SET rated_participant_count = :count, updated_at = :now
             WHERE id = :contestId
            """, nativeQuery = true)
    int recordRatedCount(@Param("contestId") long contestId,
                         @Param("count") int count,
                         @Param("now") Instant now);

    /**
     * Contests that have ended, are rated and published, and have not been finalised.
     *
     * <p>What the sweeper works through. Deliberately a query rather than a schedule: a
     * contest can end while the application is down, and a system whose correctness depends on
     * a timer firing at a particular instant is one that silently loses contests to a restart.
     * Asking "what is outstanding" costs an index scan and cannot miss anything.
     *
     * <p>Driven by {@code ix_contests_awaiting_finalisation}, which is partial over exactly
     * this predicate.
     */
    @Query(value = """
            SELECT public_id FROM contests
             WHERE rating_finalized_at IS NULL
               AND rated = TRUE
               AND lifecycle = 'PUBLISHED'
               AND end_at <= :now
             ORDER BY end_at ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findAwaitingFinalization(@Param("now") Instant now, @Param("limit") int limit);
}
