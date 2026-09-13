package com.codearena.contest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContestParticipantRepository extends JpaRepository<ContestParticipant, Long> {

    /**
     * The participation check that gates contest submissions.
     *
     * <p>Deliberately a count rather than a load: nothing needs the row, only the fact.
     */
    @Query("""
            SELECT COUNT(p) > 0 FROM ContestParticipant p
            WHERE p.contest.publicId = :contestPublicId
              AND p.user.publicId = :userPublicId
            """)
    boolean isRegistered(@Param("contestPublicId") UUID contestPublicId,
                         @Param("userPublicId") UUID userPublicId);

    long countByContestPublicId(UUID contestPublicId);

    /**
     * One registration, by the pair that identifies it.
     *
     * <p>Separate from {@link #isRegistered} because the registration flow needs the row's
     * timestamp to answer idempotently, where the submission path needs only the fact.
     */
    @Query("SELECT p FROM ContestParticipant p WHERE p.contest.publicId = :contestPublicId "
           + "AND p.user.publicId = :userPublicId")
    Optional<ContestParticipant> findRegistration(@Param("contestPublicId") UUID contestPublicId,
                                                  @Param("userPublicId") UUID userPublicId);

    /**
     * Every registered contestant, for the standings.
     *
     * <p>Returns the projection the scoring engine wants rather than entities: the standings
     * need a username and two ids, and loading full users to discard everything else is the
     * difference between one query and one query plus a lot of wasted rows.
     *
     * <p>Ordered by id so that the input to scoring is itself deterministic.
     */
    @Query("""
            SELECT new com.codearena.contest.ContestScoring$Contestant(
                p.user.publicId, p.user.username, p.user.id)
            FROM ContestParticipant p
            WHERE p.contest.publicId = :contestPublicId
            ORDER BY p.user.id ASC
            """)
    List<ContestScoring.Contestant> findContestants(@Param("contestPublicId") UUID contestPublicId);

    /** The admin participant list. */
    @Query("""
            SELECT p FROM ContestParticipant p
            JOIN FETCH p.user
            WHERE p.contest.publicId = :contestPublicId
            ORDER BY p.registeredAt ASC
            """)
    List<ContestParticipant> findForAdmin(@Param("contestPublicId") UUID contestPublicId);
}
