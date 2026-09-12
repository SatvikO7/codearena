package com.codearena.submission;

import com.codearena.shared.SubmissionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * Loads a submission with its problem and author in one round trip.
     *
     * <p>The detail response names the problem and checks ownership, so both associations
     * are needed every time; leaving them lazy would mean two extra queries per request.
     */
    @EntityGraph(attributePaths = {"problem", "user"})
    Optional<Submission> findByPublicId(UUID publicId);

    /**
     * One user's history, newest first.
     *
     * <p>Scoped by user id in the query itself rather than filtered afterwards: a listing
     * that fetched everything and then removed other people's rows would be one forgotten
     * line away from leaking them, and would read the whole table to do it.
     */
    @EntityGraph(attributePaths = {"problem"})
    @Query("""
            SELECT s FROM Submission s
            WHERE s.user.id = :userId
              AND (:problemId IS NULL OR s.problem.id = :problemId)
              AND (:status IS NULL OR s.status = :status)
            """)
    Page<Submission> findForUser(@Param("userId") Long userId,
                                 @Param("problemId") Long problemId,
                                 @Param("status") SubmissionStatus status,
                                 Pageable pageable);

    // ------------------------------------------------------- recovery sweeper

    /**
     * Submissions that should be in Redis but may not be.
     *
     * <p>Either the publication was never confirmed ({@code enqueued_at IS NULL} — the
     * process died between commit and push), or it was confirmed long enough ago that the
     * job has evidently been lost. Both are republished; a duplicate delivery is harmless
     * because claiming is atomic.
     */
    @Query("""
            SELECT s FROM Submission s
            WHERE s.status = com.codearena.shared.SubmissionStatus.QUEUED
              AND (s.enqueuedAt IS NULL OR s.enqueuedAt < :staleBefore)
            ORDER BY s.id ASC
            """)
    List<Submission> findUnpublished(@Param("staleBefore") Instant staleBefore, Pageable limit);

    /**
     * Submissions whose worker has gone silent.
     *
     * <p>A claim is a lease. If it has not produced a result within the lease window the
     * worker is presumed dead, and the submission is either returned to the queue or, once
     * it has burned through its attempts, failed as SYSTEM_ERROR.
     */
    @Query("""
            SELECT s FROM Submission s
            WHERE s.status = com.codearena.shared.SubmissionStatus.RUNNING
              AND s.claimedAt < :expiredBefore
            ORDER BY s.id ASC
            """)
    List<Submission> findExpiredClaims(@Param("expiredBefore") Instant expiredBefore, Pageable limit);

    /**
     * Marks publication in a single statement, without loading the entity.
     *
     * <p>Runs after the creating transaction has already committed, so it must not depend
     * on a managed instance from that transaction.
     */
    @Modifying
    @Query("UPDATE Submission s SET s.enqueuedAt = :at, s.updatedAt = :at WHERE s.publicId = :publicId")
    int markEnqueued(@Param("publicId") UUID publicId, @Param("at") Instant at);

    long countByUserIdAndStatusIn(Long userId, List<SubmissionStatus> statuses);
}
