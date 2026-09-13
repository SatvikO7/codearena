package com.codearena.contest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContestProblemRepository extends JpaRepository<ContestProblem, Long> {

    /**
     * The authorisation check behind every contest submission.
     *
     * <p>This is what stops a contestant submitting to a problem that is not in the contest
     * by pairing a contest id with any problem id they happen to know. The relationship is
     * verified in the database, not inferred from the request.
     */
    @Query("""
            SELECT cp FROM ContestProblem cp
            JOIN FETCH cp.problem
            WHERE cp.contest.publicId = :contestPublicId
              AND cp.problem.publicId = :problemPublicId
            """)
    Optional<ContestProblem> findEntry(@Param("contestPublicId") UUID contestPublicId,
                                       @Param("problemPublicId") UUID problemPublicId);

    boolean existsByContestPublicIdAndProblemPublicId(UUID contestPublicId, UUID problemPublicId);

    @Query("SELECT COALESCE(MAX(cp.displayOrder), -1) FROM ContestProblem cp WHERE cp.contest.id = :contestId")
    int highestDisplayOrder(@Param("contestId") long contestId);
}
