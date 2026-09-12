package com.codearena.problem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Problem lookups.
 *
 * <p>Every listing query filters, sorts and pages in the database. Nothing here returns
 * an unbounded list: loading the catalogue into memory to slice it in Java would be a
 * bug that only shows up once the catalogue is large.
 *
 * <p>The filter parameters are deliberately typed ({@link Difficulty}, {@link ProblemStatus},
 * {@link ProblemTag}) rather than strings, and the queries are JPQL with bound parameters.
 * No fragment of any query is assembled from client input, so there is no string for an
 * injection to travel in.
 *
 * <p>The search term arrives pre-built as a LIKE pattern rather than being wrapped in
 * {@code LOWER(CONCAT('%', :search, '%'))} inside the query. Applying a function to a
 * parameter that may be null leaves PostgreSQL unable to infer its type: it falls back to
 * {@code bytea} and the statement dies with "function lower(bytea) does not exist", even
 * on the requests that pass no search term at all. Comparing against a bare parameter
 * sidesteps that, and lower-casing the term once in Java beats doing it per row.
 */
public interface ProblemRepository extends JpaRepository<Problem, Long> {

    boolean existsBySlug(String slug);

    /** True when some *other* problem already holds this slug — the rename check. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    Optional<Problem> findByPublicId(UUID publicId);

    Optional<Problem> findBySlug(String slug);

    /**
     * The public catalogue.
     *
     * <p>{@code status} is fixed by the caller to PUBLISHED rather than accepted from the
     * request, so a crafted query parameter cannot widen the result set to drafts. Each
     * filter is skipped when its parameter is null, which keeps one query serving every
     * combination instead of building SQL by hand.
     *
     * <p>The tag predicate uses {@code MEMBER OF}, which Hibernate renders as an EXISTS
     * subquery rather than a join. That matters: joining the tag collection would emit one
     * row per matching tag, so a problem carrying three tags would appear three times and
     * the total count would be wrong.
     */
    @Query("""
            SELECT p FROM Problem p
            WHERE p.status = :status
              AND (:difficulty IS NULL OR p.difficulty = :difficulty)
              AND (:tag IS NULL OR :tag MEMBER OF p.tags)
              AND (:searchPattern IS NULL
                   OR LOWER(p.title) LIKE :searchPattern ESCAPE '!'
                   OR LOWER(p.slug)  LIKE :searchPattern ESCAPE '!')
            """)
    Page<Problem> findCatalogue(@Param("status") ProblemStatus status,
                                @Param("difficulty") Difficulty difficulty,
                                @Param("tag") ProblemTag tag,
                                @Param("searchPattern") String searchPattern,
                                Pageable pageable);

    /**
     * The administrative listing. Unlike the catalogue, status is a caller-supplied filter
     * because an administrator is allowed to see every status; null means "all".
     */
    @Query("""
            SELECT p FROM Problem p
            WHERE (:status IS NULL OR p.status = :status)
              AND (:difficulty IS NULL OR p.difficulty = :difficulty)
              AND (:searchPattern IS NULL
                   OR LOWER(p.title) LIKE :searchPattern ESCAPE '!'
                   OR LOWER(p.slug)  LIKE :searchPattern ESCAPE '!')
            """)
    Page<Problem> findForAdmin(@Param("status") ProblemStatus status,
                               @Param("difficulty") Difficulty difficulty,
                               @Param("searchPattern") String searchPattern,
                               Pageable pageable);

    /**
     * Loads a problem with its examples and tags in one round trip.
     *
     * <p>The detail page renders both, and without this the lazy collections would each
     * trigger their own query. Test cases are deliberately absent: the user-facing detail
     * view must never touch them.
     */
    @EntityGraph(attributePaths = {"examples", "tags"})
    @Query("SELECT p FROM Problem p WHERE p.slug = :slug AND p.status = :status")
    Optional<Problem> findPublishedBySlugWithDetail(@Param("slug") String slug,
                                                    @Param("status") ProblemStatus status);

    /**
     * The admin detail view.
     *
     * <p>The graph fetches examples and tags but deliberately not test cases. Hibernate
     * cannot fetch two {@code List} collections in a single query — it raises
     * {@code MultipleBagFetchException}, because the join would produce the cartesian
     * product of the two and it cannot tell which duplicate rows are real. Test cases
     * therefore load lazily: one extra query on an admin-only path, which is a fair price
     * for keeping the mapping honest. Every caller runs inside a transaction, so the
     * session is open when that happens.
     */
    @EntityGraph(attributePaths = {"examples", "tags"})
    Optional<Problem> findWithDetailByPublicId(UUID publicId);
}
