package com.codearena.contest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The standings aggregation, done by the database.
 *
 * <h2>Why these are native queries</h2>
 * Both are aggregations over a window of one contest's submissions, and both need
 * PostgreSQL constructs that JPQL cannot express. More importantly, the alternative —
 * loading a contest's submissions and grouping them in Java — is the thing this class exists
 * to avoid: a contest with 300 contestants and 10 problems can easily have tens of thousands
 * of submissions, and none of them need to travel over the wire to answer "who solved what,
 * and when".
 *
 * <p>What comes back is <b>one row per solved (contestant, problem) cell</b>, which is
 * bounded by contestants × problems regardless of how many times people submitted.
 *
 * <h2>Why the penalised verdicts are a parameter</h2>
 * They are passed in from {@link ContestScoring#PENALISED_VERDICTS} rather than written into
 * the SQL. One definition, used by the query and by the scoring, so a change to the rule
 * cannot leave the two disagreeing — which would produce a standings page that is internally
 * inconsistent and very hard to explain.
 *
 * <p>Both queries are driven by {@code ix_submissions_contest_scoring}, the partial index on
 * {@code (contest_id, user_id, problem_id, created_at)}.
 */
public interface ContestStandingsRepository extends JpaRepository<ContestParticipant, Long> {

    /** One solved cell: who, which problem, when, and how many rejections preceded it. */
    interface SolveRow {
        UUID getUserPublicId();

        long getProblemId();

        Instant getSolvedAt();

        int getWrongBefore();
    }

    /** One cell's total counted attempts, for the display grid. */
    interface AttemptRow {
        UUID getUserPublicId();

        long getProblemId();

        int getAttempts();
    }

    /**
     * Every solved (contestant, problem) cell in a contest.
     *
     * <p>{@code MIN(created_at)} is the solve: the <em>first</em> accepted submission. Later
     * accepted submissions to the same problem are collapsed by the GROUP BY and change
     * nothing, which is what makes a resubmission harmless.
     *
     * <p>The correlated subquery counts only rejections made <b>strictly before</b> that
     * moment. Attempts after a solve cannot have contributed to it and are not charged.
     */
    @Query(value = """
            WITH solved AS (
                SELECT s.user_id, s.problem_id, MIN(s.created_at) AS solved_at
                FROM submissions s
                WHERE s.contest_id = :contestId
                  AND s.status = 'ACCEPTED'
                GROUP BY s.user_id, s.problem_id
            )
            SELECT u.public_id      AS userPublicId,
                   solved.problem_id AS problemId,
                   solved.solved_at  AS solvedAt,
                   (SELECT COUNT(*)
                      FROM submissions w
                     WHERE w.contest_id = :contestId
                       AND w.user_id    = solved.user_id
                       AND w.problem_id = solved.problem_id
                       AND w.created_at < solved.solved_at
                       AND w.status IN (:penalised)) AS wrongBefore
            FROM solved
            JOIN users u ON u.id = solved.user_id
            """, nativeQuery = true)
    List<SolveRow> findSolves(@Param("contestId") long contestId,
                              @Param("penalised") Collection<String> penalisedVerdicts);

    /**
     * Counted attempts per cell, whether or not the problem was solved.
     *
     * <p>Used only to show "3 attempts" in the grid. The penalty comes from
     * {@link #findSolves}, because only attempts before a solve are charged.
     */
    @Query(value = """
            SELECT u.public_id AS userPublicId,
                   s.problem_id AS problemId,
                   COUNT(*)     AS attempts
            FROM submissions s
            JOIN users u ON u.id = s.user_id
            WHERE s.contest_id = :contestId
              AND s.status IN (:penalised)
            GROUP BY u.public_id, s.problem_id
            """, nativeQuery = true)
    List<AttemptRow> findAttemptCounts(@Param("contestId") long contestId,
                                       @Param("penalised") Collection<String> penalisedVerdicts);
}
