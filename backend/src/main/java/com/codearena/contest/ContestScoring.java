package com.codearena.contest;

import com.codearena.shared.SubmissionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The scoring rules, as a pure function.
 *
 * <h2>The model: ICPC-style, stated precisely</h2>
 *
 * <p><b>A problem is solved</b> by a contestant's <em>first</em> ACCEPTED submission to it
 * within the contest. Later submissions to an already-solved problem change nothing — not
 * the score, not the penalty, not the solve time. A contestant cannot lose points by
 * submitting again, and cannot gain them by submitting the same thing twice.
 *
 * <p><b>Score</b> is the sum of the points of the problems solved. Unsolved problems
 * contribute nothing; there is no partial credit.
 *
 * <p><b>Penalty</b> is accumulated only over <em>solved</em> problems. For each one:
 *
 * <pre>
 *   penalty(problem) = minutes from contest start to the accepted submission
 *                    + 20 × (counted wrong attempts made before that accepted submission)
 * </pre>
 *
 * <p>Two consequences that are deliberate and worth being explicit about:
 * <ul>
 *   <li><b>Wrong attempts on a problem you never solve are free.</b> This is the standard
 *       ICPC rule. Penalising unsolved attempts would mean a contestant who tried a hard
 *       problem and failed finishes below an identical contestant who did not try at all,
 *       which punishes effort rather than error.</li>
 *   <li><b>Wrong attempts made <em>after</em> solving are free</b>, because they cannot have
 *       helped; they are usually a resubmission after an edit.</li>
 * </ul>
 *
 * <h2>Which verdicts are a "wrong attempt"</h2>
 * WRONG_ANSWER, RUNTIME_ERROR, TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED and
 * COMPILATION_ERROR all count. Each is a judgement on submitted code.
 *
 * <p><b>SYSTEM_ERROR never counts.</b> It means the judge failed — a sandbox that would not
 * start, a daemon that went away — and the code was never shown to be wrong. Charging
 * twenty penalty minutes for our own outage would be the platform taking its failures out on
 * the people using it. QUEUED and RUNNING do not count either: nothing has been judged yet,
 * and a submission still in flight is not evidence of anything.
 *
 * <h2>Ordering</h2>
 * <ol>
 *   <li>Higher score first.</li>
 *   <li>Then lower penalty.</li>
 *   <li>Then the earlier <em>last</em> solve — of two contestants with the same score and
 *       penalty, the one who finished sooner ranks higher.</li>
 *   <li>Then user id ascending, purely so the order is stable. Two contestants who are
 *       genuinely tied get adjacent ranks in an arbitrary but <em>repeatable</em> order,
 *       rather than swapping places between two reads of the same data.</li>
 * </ol>
 *
 * <p>Ranks are <b>competition ranks</b>: genuinely tied contestants share a rank and the
 * next rank skips (1, 2, 2, 4). "Genuinely tied" means equal on score, penalty and last
 * solve — the id tie-break orders them but does not separate them, because being 400th in
 * the database is not an achievement.
 *
 * <h2>Why this class has no dependencies</h2>
 * It takes facts and returns rows. No repository, no clock, no entity, no Spring. Every
 * rule above is therefore testable without a database, and the tests read as a specification
 * of the rules rather than of the query that fetched them.
 */
public final class ContestScoring {

    /** Minutes added to the penalty for each rejected attempt before a solve. */
    public static final int PENALTY_MINUTES_PER_WRONG_ATTEMPT = 20;

    /**
     * The verdicts that count as a failed attempt.
     *
     * <p>SYSTEM_ERROR is absent, and that absence is the rule rather than an oversight: a
     * judge failure is ours, not the contestant's.
     */
    public static final Set<SubmissionStatus> PENALISED_VERDICTS = EnumSet.of(
            SubmissionStatus.WRONG_ANSWER,
            SubmissionStatus.RUNTIME_ERROR,
            SubmissionStatus.TIME_LIMIT_EXCEEDED,
            SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
            SubmissionStatus.COMPILATION_ERROR);

    private ContestScoring() {
    }

    /**
     * One contestant's solve of one problem, as read from the submission history.
     *
     * @param wrongAttemptsBefore counted rejections strictly before {@code solvedAt}
     */
    public record Solve(UUID userPublicId, long problemId, Instant solvedAt, int wrongAttemptsBefore) {
    }

    /** A contestant who is registered, whether or not they solved anything. */
    public record Contestant(UUID userPublicId, String username, long userId) {
    }

    /** What one contestant did with one problem, for the standings grid. */
    public record ProblemResult(long problemId, boolean solved, int attempts,
                                Integer penaltyMinutes, Instant solvedAt) {
    }

    /** One row of the standings. */
    public record Row(int rank, UUID userPublicId, String username,
                      int solvedCount, int score, int penalty,
                      List<ProblemResult> problems) {
    }

    /**
     * Computes the standings.
     *
     * @param contestants every registered user; those who solved nothing still appear
     * @param solves      one entry per (contestant, problem) actually solved
     * @param attempts    counted wrong attempts per (contestant, problem), including those
     *                    after a solve — used for the display grid, not for the penalty
     * @param points      problem id to its points in this contest
     * @param startAt     the contest's start, the origin for solve times
     */
    public static List<Row> standings(List<Contestant> contestants,
                                      List<Solve> solves,
                                      Map<AttemptKey, Integer> attempts,
                                      Map<Long, Integer> points,
                                      Instant startAt) {

        Map<UUID, List<Solve>> byUser = solves.stream()
                .collect(Collectors.groupingBy(Solve::userPublicId));

        List<Scored> scored = new ArrayList<>(contestants.size());
        for (Contestant contestant : contestants) {
            List<Solve> theirs = byUser.getOrDefault(contestant.userPublicId(), List.of());

            int score = 0;
            int penalty = 0;
            Instant lastSolve = null;
            List<ProblemResult> results = new ArrayList<>();

            for (Long problemId : points.keySet().stream().sorted().toList()) {
                Solve solve = theirs.stream()
                        .filter(candidate -> candidate.problemId() == problemId)
                        .findFirst()
                        .orElse(null);
                int attemptCount = attempts.getOrDefault(
                        new AttemptKey(contestant.userPublicId(), problemId), 0);

                if (solve == null) {
                    results.add(new ProblemResult(problemId, false, attemptCount, null, null));
                    continue;
                }

                int problemPenalty = penaltyFor(startAt, solve);
                score += points.getOrDefault(problemId, 0);
                penalty += problemPenalty;
                if (lastSolve == null || solve.solvedAt().isAfter(lastSolve)) {
                    lastSolve = solve.solvedAt();
                }
                results.add(new ProblemResult(problemId, true, attemptCount,
                        problemPenalty, solve.solvedAt()));
            }

            scored.add(new Scored(contestant, theirs.size(), score, penalty, lastSolve, results));
        }

        scored.sort(ORDER);
        return rank(scored);
    }

    /**
     * The penalty contributed by one solved problem.
     *
     * <p>Minutes are truncated, not rounded: a solve at 4 minutes 59 seconds costs 4, which
     * is the conventional behaviour and means the number never exceeds the elapsed time.
     * A solve before the start would be a bug elsewhere, but is clamped to zero rather than
     * producing a negative penalty that would sort someone to the top.
     */
    private static int penaltyFor(Instant startAt, Solve solve) {
        long minutes = Duration.between(startAt, solve.solvedAt()).toMinutes();
        long elapsed = Math.max(0, minutes);
        return (int) elapsed + PENALTY_MINUTES_PER_WRONG_ATTEMPT * solve.wrongAttemptsBefore();
    }

    /**
     * Competition ranking: ties share a rank, and the next rank skips.
     *
     * <p>The id tie-break in {@link #ORDER} makes the <em>order</em> deterministic; it does
     * not make two identical contestants differently ranked. Those are separate questions
     * and conflating them would award a better rank for having registered earlier.
     */
    private static List<Row> rank(List<Scored> scored) {
        List<Row> rows = new ArrayList<>(scored.size());
        int rank = 0;
        Scored previous = null;

        for (int i = 0; i < scored.size(); i++) {
            Scored current = scored.get(i);
            if (previous == null || !tiedWith(previous, current)) {
                rank = i + 1;
            }
            rows.add(new Row(rank, current.contestant().userPublicId(),
                    current.contestant().username(), current.solvedCount(),
                    current.score(), current.penalty(), current.results()));
            previous = current;
        }
        return rows;
    }

    private static boolean tiedWith(Scored a, Scored b) {
        return a.score() == b.score()
                && a.penalty() == b.penalty()
                && java.util.Objects.equals(a.lastSolve(), b.lastSolve());
    }

    /** Score desc, penalty asc, last solve asc, then user id for stability. */
    private static final Comparator<Scored> ORDER = Comparator
            .comparingInt(Scored::score).reversed()
            .thenComparingInt(Scored::penalty)
            .thenComparing(Scored::lastSolve, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(scored -> scored.contestant().userId());

    /** Identifies a (contestant, problem) cell. */
    public record AttemptKey(UUID userPublicId, long problemId) {
    }

    private record Scored(Contestant contestant, int solvedCount, int score, int penalty,
                          Instant lastSolve, List<ProblemResult> results) {
    }
}
