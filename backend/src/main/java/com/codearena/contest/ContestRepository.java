package com.codearena.contest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    Optional<Contest> findByPublicId(UUID publicId);

    /**
     * Loads a contest with its problems in one query.
     *
     * <p>Only the {@code problems} collection is fetched. Each {@code ContestProblem} still
     * holds a lazy {@code Problem}, which is fine for the admin view but would be an N+1 for
     * the contestant view — {@link #findForContestantView} exists for that.
     */
    @EntityGraph(attributePaths = "problems")
    Optional<Contest> findWithProblemsByPublicId(UUID publicId);

    /**
     * Loads a contest with its problems and the problems themselves.
     *
     * <p>One query for the whole contest page. Without the second hop a twelve-problem
     * contest issues twelve extra selects to render its problem list.
     */
    @Query("""
            SELECT DISTINCT c FROM Contest c
            LEFT JOIN FETCH c.problems cp
            LEFT JOIN FETCH cp.problem
            WHERE c.publicId = :publicId
            """)
    Optional<Contest> findForContestantView(@Param("publicId") UUID publicId);

    boolean existsBySlugIgnoreCase(String slug);

    /** Used when updating a contest, so a contest does not collide with itself. */
    boolean existsBySlugIgnoreCaseAndPublicIdNot(String slug, UUID publicId);

    /**
     * The public catalogue: everything a user is allowed to see.
     *
     * <p>Drafts are excluded <b>here</b>, in the query, rather than filtered after loading.
     * A draft that never leaves the database cannot leak through a serialisation mistake or
     * a forgotten branch. Cancelled contests remain visible: people registered for them and
     * are entitled to see that they were called off.
     */
    @Query("""
            SELECT c FROM Contest c
            WHERE c.lifecycle <> com.codearena.contest.ContestLifecycle.DRAFT
            """)
    Page<Contest> findPublicContests(Pageable pageable);

    /** Administrators see everything, drafts included. */
    @Query("SELECT c FROM Contest c")
    Page<Contest> findAllForAdmin(Pageable pageable);

    /**
     * Contests a user is registered for, newest first.
     *
     * <p>A join rather than a collection on the entity: a contest's participant list can be
     * thousands of rows and has no business being loaded to answer "am I in this one?".
     */
    @Query("""
            SELECT c FROM Contest c
            JOIN ContestParticipant p ON p.contest = c
            WHERE p.user.publicId = :userPublicId
              AND c.lifecycle <> com.codearena.contest.ContestLifecycle.DRAFT
            ORDER BY c.startAt DESC
            """)
    List<Contest> findRegisteredFor(@Param("userPublicId") UUID userPublicId);
}
