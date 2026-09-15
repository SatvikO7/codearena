package com.codearena.rating;

import com.codearena.audit.ActorType;
import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.common.ValidationException;
import com.codearena.contest.Contest;
import com.codearena.contest.ContestLifecycle;
import com.codearena.contest.ContestRepository;
import com.codearena.contest.ContestScoring;
import com.codearena.contest.ContestStandingsRepository;
import com.codearena.contest.ContestStandingsService;
import com.codearena.contest.ContestStatus;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a finished contest into rating changes, exactly once.
 *
 * <h2>The shape of the operation</h2>
 * <pre>
 *   BEGIN
 *     claim the contest            ← one conditional UPDATE; only one caller wins
 *     compute the final standings  ← from the database, never from a request
 *     compute the rating changes   ← RatingCalculator, pure
 *     update every user's rating
 *     insert every history row
 *     record the participant count
 *   COMMIT
 * </pre>
 *
 * <p>All of it, or none of it. If anything throws — a constraint violation, a lost row, a
 * database hiccup — the transaction rolls back and the contest is not finalised: the claim is
 * released along with everything else, and the next attempt starts from the same clean state.
 * <b>There is no path that updates half the field.</b> A contest where forty of eighty
 * competitors had been rated would be unrecoverable by any automatic means, because there
 * would be no way to tell which forty.
 *
 * <h2>Idempotence</h2>
 * A second call does not throw and does not change anything. It returns the outcome of the
 * finalisation that already happened. That matters because the three ways in — an
 * administrator, the sweeper, and a retry after a timeout — will genuinely overlap, and a
 * caller who receives an error for a thing that already succeeded is a caller who retries.
 */
@Service
public class ContestFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(ContestFinalizationService.class);

    private final ContestRepository contestRepository;
    private final ContestFinalizationRepository finalizationRepository;
    private final ContestStandingsService standingsService;
    private final ContestStandingsRepository standingsRepository;
    private final UserRatingRepository userRatingRepository;
    private final ContestRatingChangeRepository ratingChangeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RatingMetrics metrics;
    private final Clock clock;

    public ContestFinalizationService(ContestRepository contestRepository,
                                      ContestFinalizationRepository finalizationRepository,
                                      ContestStandingsService standingsService,
                                      ContestStandingsRepository standingsRepository,
                                      UserRatingRepository userRatingRepository,
                                      ContestRatingChangeRepository ratingChangeRepository,
                                      UserRepository userRepository,
                                      AuditService auditService,
                                      RatingMetrics metrics,
                                      Clock clock) {
        this.contestRepository = contestRepository;
        this.finalizationRepository = finalizationRepository;
        this.standingsService = standingsService;
        this.standingsRepository = standingsRepository;
        this.userRatingRepository = userRatingRepository;
        this.ratingChangeRepository = ratingChangeRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * What a finalisation did.
     *
     * @param alreadyFinalized true when this call found the work already done. Not an error
     * @param rated            whether the contest was configured to move ratings
     * @param ratedParticipants how many competitors received a change
     */
    public record Result(
            UUID contestId,
            boolean alreadyFinalized,
            boolean rated,
            int ratedParticipants,
            Instant finalizedAt) {
    }

    /**
     * Who asked for a finalisation.
     *
     * <p>Only affects how the event is attributed, and that attribution has to be right. The
     * sweeper runs with no security context, so resolving the actor the usual way would record
     * every automatic finalisation as ANONYMOUS — an audit log that says an unauthenticated
     * caller rated a contest is worse than no audit log, because it is wrong in a way somebody
     * would act on.
     */
    public enum Source {
        /** An administrator called the endpoint. Attributed to them. */
        ADMIN,
        /** The background sweeper found an outstanding contest. Attributed to SYSTEM. */
        SWEEPER
    }

    /**
     * Finalises a contest, or reports that it already was.
     *
     * @throws ResourceNotFoundException if no such contest exists
     * @throws ValidationException       if the contest has not ended, or was cancelled
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Result finalize(UUID contestId, Source source) {
        Instant now = clock.instant();
        Contest contest = contestRepository.findByPublicId(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("No such contest"));

        requireFinalizable(contest, now);

        // The claim. Exactly one concurrent caller gets 1 back; everybody else gets 0 and is
        // looking at a contest somebody else is finalising, or has finalised.
        int claimed = finalizationRepository.claimForFinalization(contest.getId(), now);
        if (claimed == 0) {
            return alreadyFinalizedResult(contestId, contest.getId());
        }

        // Reloaded, and not for tidiness. The claim is a native UPDATE with
        // clearAutomatically = true -- it has to be, or Hibernate would write a stale copy of
        // the contest back over it -- and clearing the persistence context detaches the
        // instance read above. Touching its problems after that throws
        // LazyInitializationException, which would surface as a 500 on an operation that had
        // already taken the claim: the contest would be marked finalised with no ratings, and
        // no later attempt could correct it because the claim was gone.
        //
        // Fetched with its problems, because the standings need them.
        contest = contestRepository.findWithProblemsByPublicId(contestId)
                .orElseThrow(() -> new IllegalStateException(
                        "contest vanished between the claim and the finalisation"));

        long startedAt = System.nanoTime();
        List<RatingCalculator.Change> changes = contest.isRated()
                ? computeChanges(contest)
                : List.of();

        for (RatingCalculator.Change change : changes) {
            apply(change, contest, changes.size(), now);
        }
        finalizationRepository.recordRatedCount(contest.getId(), changes.size(), now);

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        metrics.finalized(contest.isRated(), changes.size(), durationMs);

        // In the caller's transaction (MANDATORY either way), so a rolled-back finalisation
        // leaves no event claiming it happened -- and a finalisation cannot commit unaudited.
        Map<String, Object> metadata = AuditMetadata.of()
                .put("source", source.name().toLowerCase(Locale.ROOT))
                .put("rated", contest.isRated())
                .put("ratedParticipants", changes.size())
                .put("durationMs", durationMs)
                .build();

        if (source == Source.SWEEPER) {
            auditService.recordFor(null, null, ActorType.SYSTEM,
                    AuditAction.CONTEST_FINALIZE, AuditOutcome.SUCCESS,
                    AuditEntityType.CONTEST, contestId.toString(), metadata);
        } else {
            auditService.record(AuditAction.CONTEST_FINALIZE, AuditOutcome.SUCCESS,
                    AuditEntityType.CONTEST, contestId.toString(), metadata);
        }

        log.info("event=CONTEST_FINALIZED contest={} source={} rated={} participants={} durationMs={}",
                contestId, source, contest.isRated(), changes.size(), durationMs);

        return new Result(contestId, false, contest.isRated(), changes.size(), now);
    }

    /**
     * The three reasons a contest cannot be finalised now.
     *
     * <p>Checked before the claim, so a contest that is not eligible is never marked as
     * finalised. Being already finalised is <em>not</em> one of them — that is the idempotent
     * path, and it is decided by the claim rather than here, because checking it here would
     * reintroduce exactly the read-then-write race the claim exists to remove.
     */
    private void requireFinalizable(Contest contest, Instant now) {
        if (contest.getLifecycle() == ContestLifecycle.CANCELLED) {
            // A cancelled contest never produces a rating, whatever its standings show. The
            // submissions and the scoreboard remain readable as a record of what happened;
            // what is withheld is the consequence.
            throw new ValidationException("lifecycle",
                    "A cancelled contest is never rated");
        }
        if (contest.getLifecycle() != ContestLifecycle.PUBLISHED) {
            throw new ValidationException("lifecycle",
                    "Only a published contest can be finalised");
        }
        if (contest.statusAt(now) != ContestStatus.ENDED) {
            throw new ValidationException("status",
                    "A contest can only be finalised after it has ended");
        }
    }

    /**
     * Assembles the calculator's input from the authoritative standings.
     *
     * <p>Every number here comes from the database. Nothing is taken from a request, a cache
     * or a client — a rating that could be influenced by what somebody posted would not be a
     * rating.
     */
    private List<RatingCalculator.Change> computeChanges(Contest contest) {
        Map<Long, Integer> points = standingsService.pointsOf(contest);
        List<ContestScoring.Row> rows = standingsService.computeRows(contest, points);

        // Eligibility. The standings list every registered contestant; the rating covers only
        // the ones who submitted something. See findParticipantsWhoSubmitted for why.
        //
        // The surviving rows keep the ranks the scoreboard gave them -- they are not
        // renumbered. That is both deliberate and safe. Deliberate, because a rating change
        // has to be explained by the standings somebody can look at, and a rank that existed
        // only inside the rating calculation would explain nothing. Safe, because a no-show
        // scores zero and, since penalty is only charged on solved problems, carries no
        // penalty either -- so a no-show is strictly better than nobody, and removing one
        // cannot change any other contestant's rank. The highest surviving rank therefore
        // never exceeds the number of survivors, which is what ck_rating_changes_participants
        // requires.
        Set<UUID> competed = Set.copyOf(
                standingsRepository.findParticipantsWhoSubmitted(contest.getId()));
        rows = rows.stream().filter(row -> competed.contains(row.userPublicId())).toList();

        if (rows.size() < RatingCalculator.MIN_PARTICIPANTS) {
            return List.of();
        }

        // One query for the whole field rather than one per competitor.
        Map<UUID, User> users = new HashMap<>();
        for (User user : userRepository.findAllByPublicIdIn(
                rows.stream().map(ContestScoring.Row::userPublicId).toList())) {
            users.put(user.getPublicId(), user);
        }
        Map<Long, UserRating> ratings = new HashMap<>();
        for (UserRating rating : userRatingRepository.findAllByUserIdIn(
                users.values().stream().map(User::getId).toList())) {
            ratings.put(rating.getUser().getId(), rating);
        }

        List<RatingCalculator.Participant> participants = new ArrayList<>(rows.size());
        for (ContestScoring.Row row : rows) {
            User user = users.get(row.userPublicId());
            if (user == null) {
                // The account was removed between the standings query and this one. Dropping
                // it is the only honest option: there is nobody left to rate.
                continue;
            }
            UserRating existing = ratings.get(user.getId());
            participants.add(new RatingCalculator.Participant(
                    row.userPublicId(),
                    existing == null ? RatingCalculator.INITIAL_RATING : existing.getRating(),
                    existing == null ? 0 : existing.getContestsRated(),
                    row.rank(),
                    row.score(),
                    row.penalty()));
        }
        return RatingCalculator.compute(participants);
    }

    /**
     * Writes one competitor's change: the rating, and the record explaining it.
     *
     * <p>Both, or the transaction fails. A rating that moved without a history row would be a
     * number nobody could account for.
     */
    private void apply(RatingCalculator.Change change, Contest contest,
                       int fieldSize, Instant now) {
        User user = userRepository.findByPublicId(change.userPublicId())
                .orElseThrow(() -> new IllegalStateException(
                        "participant vanished during finalisation"));

        UserRating rating = userRatingRepository.findByUserId(user.getId())
                .orElseGet(() -> UserRating.starting(user, now));

        rating.applyContestResult(change.ratingAfter(), contest, now);
        userRatingRepository.save(rating);

        // The field size is passed down rather than held on the service. This class is a
        // singleton and two contests can finalise at once, so a mutable field here would
        // be exactly the race the rest of the class exists to prevent.
        ratingChangeRepository.save(new ContestRatingChange(
                user.getId(), contest.getId(), change, fieldSize, now));
    }
    private Result alreadyFinalizedResult(UUID contestId, long internalId) {
        Contest reloaded = contestRepository.findByPublicId(contestId).orElseThrow();
        long rated = ratingChangeRepository.countByContestId(internalId);
        metrics.finalizationSkipped();
        log.info("event=CONTEST_FINALIZE_SKIPPED contest={} reason=already_finalized", contestId);
        return new Result(contestId, true, reloaded.isRated(), (int) rated,
                reloaded.getRatingFinalizedAt());
    }
}
