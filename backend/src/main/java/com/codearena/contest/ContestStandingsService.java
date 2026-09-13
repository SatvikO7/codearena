package com.codearena.contest;

import com.codearena.contest.dto.StandingsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the scoreboard.
 *
 * <h2>Where the numbers come from</h2>
 * Persisted submission results, and nothing else. Three bounded queries — the contestants,
 * the solved cells, the attempt counts — and then {@link ContestScoring}, which is a pure
 * function. No score is stored anywhere, so there is no cached total to fall out of step
 * with the submissions it was computed from, and no incremental update to get wrong when a
 * result is recorded twice.
 *
 * <h2>Cost</h2>
 * The aggregation happens in PostgreSQL. What crosses the wire is one row per contestant and
 * one per solved cell — bounded by contestants × problems, not by the number of submissions.
 * A contest where everyone submits fifty times costs the same to score as one where everyone
 * submits once.
 *
 * <p>There is deliberately no cache. A cached scoreboard has to be invalidated when a
 * submission reaches a terminal state, and an invalidation that is missed shows impossible
 * rankings until somebody notices. Computing it costs three indexed queries; that is the
 * right trade at this scale, and the point at which it stops being so is a measurable one
 * rather than a guess. See ADR-034.
 *
 * <h2>Visibility</h2>
 * Standings are public for any contest that has started, and visible <em>during</em> a live
 * contest. Hiding them would need a freeze policy, and a frozen scoreboard is a feature with
 * its own rules rather than a default.
 */
@Service
public class ContestStandingsService {

    private static final Logger log = LoggerFactory.getLogger(ContestStandingsService.class);

    private final ContestService contestService;
    private final ContestParticipantRepository participantRepository;
    private final ContestStandingsRepository standingsRepository;
    private final Clock clock;

    public ContestStandingsService(ContestService contestService,
                                   ContestParticipantRepository participantRepository,
                                   ContestStandingsRepository standingsRepository,
                                   Clock clock) {
        this.contestService = contestService;
        this.participantRepository = participantRepository;
        this.standingsRepository = standingsRepository;
        this.clock = clock;
    }

    /**
     * The standings for one contest.
     *
     * <p>An UPCOMING contest returns an empty board rather than an error: nobody has
     * submitted anything, so there is nothing to show and nothing to hide.
     */
    @Transactional(readOnly = true)
    public StandingsResponse standings(UUID contestId) {
        Instant now = clock.instant();
        Contest contest = contestService.requireVisible(contestId);
        ContestStatus status = contest.statusAt(now);

        List<ContestProblem> entries = contest.getProblems().stream()
                .sorted(Comparator.comparingInt(ContestProblem::getDisplayOrder))
                .toList();

        List<StandingsResponse.Column> columns = entries.stream()
                .map(entry -> new StandingsResponse.Column(
                        entry.getProblem().getPublicId(), entry.label(),
                        entry.getProblem().getTitle(), entry.getPoints()))
                .toList();

        if (!status.hasStarted()) {
            return new StandingsResponse(contestId, status, now, columns, List.of());
        }

        // Insertion-ordered so the scoring engine walks problems in contest order, which
        // makes each row's cells line up with the columns above without a second sort.
        Map<Long, Integer> points = new LinkedHashMap<>();
        Map<Long, ContestProblem> byProblemId = new HashMap<>();
        for (ContestProblem entry : entries) {
            points.put(entry.getProblem().getId(), entry.getPoints());
            byProblemId.put(entry.getProblem().getId(), entry);
        }

        List<String> penalised = ContestService.penalisedVerdictNames();
        long internalId = contest.getId();

        List<ContestScoring.Contestant> contestants =
                participantRepository.findContestants(contestId);
        List<ContestScoring.Solve> solves =
                standingsRepository.findSolves(internalId, penalised).stream()
                        .map(row -> new ContestScoring.Solve(
                                row.getUserPublicId(), row.getProblemId(),
                                row.getSolvedAt(), row.getWrongBefore()))
                        .toList();

        Map<ContestScoring.AttemptKey, Integer> attempts = new HashMap<>();
        for (ContestStandingsRepository.AttemptRow row :
                standingsRepository.findAttemptCounts(internalId, penalised)) {
            attempts.put(new ContestScoring.AttemptKey(row.getUserPublicId(), row.getProblemId()),
                    row.getAttempts());
        }

        List<ContestScoring.Row> rows = ContestScoring.standings(
                contestants, solves, attempts, points, contest.getStartAt());

        log.debug("event=STANDINGS_COMPUTED contest={} contestants={} solves={}",
                contestId, contestants.size(), solves.size());

        return new StandingsResponse(contestId, status, now, columns,
                rows.stream().map(row -> toRow(row, byProblemId, contest.getStartAt())).toList());
    }

    private StandingsResponse.Row toRow(ContestScoring.Row row,
                                        Map<Long, ContestProblem> byProblemId,
                                        Instant startAt) {
        List<StandingsResponse.Cell> cells = row.problems().stream()
                .map(result -> new StandingsResponse.Cell(
                        byProblemId.get(result.problemId()).getProblem().getPublicId(),
                        result.solved(),
                        result.attempts(),
                        result.penaltyMinutes(),
                        result.solvedAt() == null
                                ? null
                                : (int) Math.max(0, Duration.between(startAt, result.solvedAt()).toMinutes())))
                .toList();

        // No email, no internal id, no profile data: a scoreboard is the most widely read
        // page a contest has and anything on it is effectively published.
        return new StandingsResponse.Row(row.rank(), row.userPublicId(), row.username(),
                row.solvedCount(), row.score(), row.penalty(), cells);
    }
}
