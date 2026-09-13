package com.codearena.contest;

import com.codearena.shared.SubmissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring rules, as executable specification.
 *
 * <p>{@link ContestScoring} takes facts and returns rows — no repository, no clock, no
 * entity — so every rule can be asserted directly against hand-written inputs. A scoring bug
 * is the worst kind of bug this system can have: it is silent, it is only noticed by the
 * person it costs a place, and by then the contest is over.
 */
class ContestScoringTest {

    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private static final long PROBLEM_A = 1L;
    private static final long PROBLEM_B = 2L;

    private static final ContestScoring.Contestant alice = new ContestScoring.Contestant(ALICE, "alice", 1L);
    private static final ContestScoring.Contestant bob = new ContestScoring.Contestant(BOB, "bob", 2L);
    private static final ContestScoring.Contestant carol = new ContestScoring.Contestant(CAROL, "carol", 3L);

    /** Insertion-ordered, so the cells of each row line up with the columns. */
    private static Map<Long, Integer> points(int a, int b) {
        Map<Long, Integer> points = new LinkedHashMap<>();
        points.put(PROBLEM_A, a);
        points.put(PROBLEM_B, b);
        return points;
    }

    private static ContestScoring.Solve solve(UUID user, long problem, int minute, int wrongBefore) {
        return new ContestScoring.Solve(user, problem, START.plusSeconds(minute * 60L), wrongBefore);
    }

    private static List<ContestScoring.Row> standings(List<ContestScoring.Contestant> contestants,
                                                      List<ContestScoring.Solve> solves) {
        return ContestScoring.standings(contestants, solves, Map.of(), points(100, 200), START);
    }

    // ----------------------------------------------------------------- the basics

    @Test
    void awardsTheProblemsPointsForASolve() {
        List<ContestScoring.Row> rows = standings(List.of(alice), List.of(solve(ALICE, PROBLEM_A, 10, 0)));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().score()).isEqualTo(100);
        assertThat(rows.getFirst().solvedCount()).isEqualTo(1);
    }

    @Test
    void awardsNothingForAnUnsolvedProblem() {
        List<ContestScoring.Row> rows = standings(List.of(alice), List.of());

        assertThat(rows.getFirst().score()).isZero();
        assertThat(rows.getFirst().penalty()).isZero();
        assertThat(rows.getFirst().solvedCount()).isZero();
    }

    /** Everyone registered appears, whether or not they submitted anything. */
    @Test
    void listsContestantsWhoSolvedNothing() {
        List<ContestScoring.Row> rows = standings(List.of(alice, bob), List.of(solve(ALICE, PROBLEM_A, 5, 0)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).username()).isEqualTo("bob");
        assertThat(rows.get(1).score()).isZero();
    }

    // ----------------------------------------------------------------- penalty

    /** A clean solve costs only the time taken. */
    @Test
    void chargesElapsedMinutesForACleanSolve() {
        List<ContestScoring.Row> rows = standings(List.of(alice), List.of(solve(ALICE, PROBLEM_A, 37, 0)));

        assertThat(rows.getFirst().penalty()).isEqualTo(37);
    }

    @Test
    void chargesTwentyMinutesPerRejectionBeforeTheSolve() {
        List<ContestScoring.Row> rows = standings(List.of(alice), List.of(solve(ALICE, PROBLEM_A, 30, 2)));

        // 30 elapsed + 2 × 20
        assertThat(rows.getFirst().penalty()).isEqualTo(70);
    }

    @Test
    void addsUpPenaltyAcrossSolvedProblems() {
        List<ContestScoring.Row> rows = standings(List.of(alice),
                List.of(solve(ALICE, PROBLEM_A, 10, 1), solve(ALICE, PROBLEM_B, 50, 0)));

        // (10 + 20) + 50
        assertThat(rows.getFirst().penalty()).isEqualTo(80);
        assertThat(rows.getFirst().score()).isEqualTo(300);
    }

    /**
     * Rejections on a problem never solved cost nothing.
     *
     * <p>The standard ICPC rule, and the right one: penalising them would rank a contestant
     * who attempted a hard problem and failed below an otherwise identical contestant who
     * never tried, which punishes effort rather than error.
     */
    @Test
    void chargesNothingForAttemptsOnAProblemNeverSolved() {
        Map<ContestScoring.AttemptKey, Integer> attempts =
                Map.of(new ContestScoring.AttemptKey(ALICE, PROBLEM_B), 5);

        List<ContestScoring.Row> rows = ContestScoring.standings(
                List.of(alice), List.of(solve(ALICE, PROBLEM_A, 10, 0)),
                attempts, points(100, 200), START);

        assertThat(rows.getFirst().penalty()).isEqualTo(10);
        assertThat(rows.getFirst().score()).isEqualTo(100);
        // The attempts are still shown in the grid, just not charged.
        ContestScoring.ProblemResult unsolved = rows.getFirst().problems().stream()
                .filter(result -> result.problemId() == PROBLEM_B).findFirst().orElseThrow();
        assertThat(unsolved.solved()).isFalse();
        assertThat(unsolved.attempts()).isEqualTo(5);
        assertThat(unsolved.penaltyMinutes()).isNull();
    }

    /** Minutes are truncated, so the charge never exceeds the time actually elapsed. */
    @Test
    void truncatesPartialMinutes() {
        ContestScoring.Solve almostFive =
                new ContestScoring.Solve(ALICE, PROBLEM_A, START.plusSeconds(299), 0);

        List<ContestScoring.Row> rows = standings(List.of(alice), List.of(almostFive));

        assertThat(rows.getFirst().penalty()).isEqualTo(4);
    }

    // ----------------------------------------------------------------- the verdict set

    /**
     * SYSTEM_ERROR is not a penalised verdict, and that is a rule rather than an omission.
     *
     * <p>It means the judge failed — a sandbox that would not start, a daemon that went
     * away. The submitted code was never shown to be wrong. Charging twenty minutes for our
     * own outage would be the platform taking its failures out on the people using it.
     */
    @Test
    void neverPenalisesASystemError() {
        assertThat(ContestScoring.PENALISED_VERDICTS).doesNotContain(SubmissionStatus.SYSTEM_ERROR);
    }

    /** Nothing still in flight counts: neither has been judged. */
    @Test
    void neverPenalisesAnUnjudgedSubmission() {
        assertThat(ContestScoring.PENALISED_VERDICTS)
                .doesNotContain(SubmissionStatus.QUEUED, SubmissionStatus.RUNNING);
    }

    /** A success is not an attempt against itself. */
    @Test
    void neverPenalisesAnAcceptedSubmission() {
        assertThat(ContestScoring.PENALISED_VERDICTS).doesNotContain(SubmissionStatus.ACCEPTED);
    }

    @Test
    void penalisesEveryVerdictThatJudgesTheCode() {
        assertThat(ContestScoring.PENALISED_VERDICTS).containsExactlyInAnyOrder(
                SubmissionStatus.WRONG_ANSWER,
                SubmissionStatus.RUNTIME_ERROR,
                SubmissionStatus.TIME_LIMIT_EXCEEDED,
                SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
                SubmissionStatus.COMPILATION_ERROR);
    }

    // ----------------------------------------------------------------- ordering

    @Test
    void ranksHigherScoreFirst() {
        List<ContestScoring.Row> rows = standings(List.of(alice, bob),
                List.of(solve(ALICE, PROBLEM_A, 10, 0), solve(BOB, PROBLEM_B, 10, 0)));

        // bob solved the 200-point problem.
        assertThat(rows.getFirst().username()).isEqualTo("bob");
        assertThat(rows.get(1).username()).isEqualTo("alice");
    }

    @Test
    void breaksAScoreTieOnLowerPenalty() {
        List<ContestScoring.Row> rows = standings(List.of(alice, bob),
                List.of(solve(ALICE, PROBLEM_A, 50, 0), solve(BOB, PROBLEM_A, 20, 0)));

        assertThat(rows.getFirst().username()).isEqualTo("bob");
        assertThat(rows.getFirst().penalty()).isEqualTo(20);
    }

    /** Equal score and penalty: whoever finished sooner ranks higher. */
    @Test
    void breaksAPenaltyTieOnTheEarlierLastSolve() {
        // Both end on penalty 40: alice 40 clean, bob 20 + one rejection.
        List<ContestScoring.Row> rows = standings(List.of(alice, bob),
                List.of(solve(ALICE, PROBLEM_A, 40, 0), solve(BOB, PROBLEM_A, 20, 1)));

        assertThat(rows.getFirst().penalty()).isEqualTo(40);
        assertThat(rows.get(1).penalty()).isEqualTo(40);
        assertThat(rows.getFirst().username()).isEqualTo("bob");
    }

    /**
     * A genuine tie is stable rather than arbitrary.
     *
     * <p>Without a final tie-break, two contestants identical on every scored dimension can
     * swap places between two reads of the same data — which looks to them like the
     * scoreboard is broken, and is.
     */
    @Test
    void ordersAGenuineTieDeterministically() {
        List<ContestScoring.Solve> solves =
                List.of(solve(ALICE, PROBLEM_A, 30, 0), solve(BOB, PROBLEM_A, 30, 0));

        List<ContestScoring.Row> first = standings(List.of(alice, bob), solves);
        List<ContestScoring.Row> reversedInput = standings(List.of(bob, alice), solves);

        assertThat(first.stream().map(ContestScoring.Row::username).toList())
                .isEqualTo(reversedInput.stream().map(ContestScoring.Row::username).toList());
    }

    /**
     * Competition ranking: equals share a rank, and the next rank skips.
     *
     * <p>The id tie-break orders the two tied contestants but must not separate them — being
     * first in the database is not an achievement.
     */
    @Test
    void givesTiedContestantsTheSameRankAndSkipsTheNext() {
        List<ContestScoring.Row> rows = standings(List.of(alice, bob, carol),
                List.of(solve(ALICE, PROBLEM_A, 30, 0),
                        solve(BOB, PROBLEM_A, 30, 0)));

        assertThat(rows.get(0).rank()).isEqualTo(1);
        assertThat(rows.get(1).rank()).isEqualTo(1);
        assertThat(rows.get(2).rank()).isEqualTo(3);
        assertThat(rows.get(2).username()).isEqualTo("carol");
    }

    @Test
    void ranksEverybodyFirstWhenNobodyHasSolvedAnything() {
        List<ContestScoring.Row> rows = standings(List.of(alice, bob, carol), List.of());

        assertThat(rows).extracting(ContestScoring.Row::rank).containsExactly(1, 1, 1);
    }

    // ----------------------------------------------------------------- resubmission

    /**
     * The engine is given one solve per problem — the first accepted one, as the query
     * guarantees — so a contestant who submits the same correct solution five more times
     * scores once and is charged once.
     */
    @Test
    void countsASolvedProblemOnceHoweverOftenItIsResubmitted() {
        Map<ContestScoring.AttemptKey, Integer> attempts =
                Map.of(new ContestScoring.AttemptKey(ALICE, PROBLEM_A), 0);

        List<ContestScoring.Row> rows = ContestScoring.standings(
                List.of(alice), List.of(solve(ALICE, PROBLEM_A, 15, 0)),
                attempts, points(100, 200), START);

        assertThat(rows.getFirst().score()).isEqualTo(100);
        assertThat(rows.getFirst().solvedCount()).isEqualTo(1);
        assertThat(rows.getFirst().penalty()).isEqualTo(15);
    }

    /** Each row carries one cell per contest problem, in the order the columns are given. */
    @Test
    void returnsOneCellPerProblemInColumnOrder() {
        List<ContestScoring.Row> rows = standings(List.of(alice), List.of(solve(ALICE, PROBLEM_B, 5, 0)));

        assertThat(rows.getFirst().problems())
                .extracting(ContestScoring.ProblemResult::problemId)
                .containsExactly(PROBLEM_A, PROBLEM_B);
        assertThat(rows.getFirst().problems().get(0).solved()).isFalse();
        assertThat(rows.getFirst().problems().get(1).solved()).isTrue();
    }
}
