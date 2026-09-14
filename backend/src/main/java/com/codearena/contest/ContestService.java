package com.codearena.contest;

import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.system.BusinessMetrics;
import com.codearena.common.ConflictException;
import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.contest.dto.ContestDetailResponse;
import com.codearena.contest.dto.ContestProblemResponse;
import com.codearena.contest.dto.ContestRegistrationResponse;
import com.codearena.contest.dto.ContestSummaryResponse;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.SubmissionRepository;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Contests as users see them: browsing, registering, and entering.
 *
 * <h2>Time comes from one place</h2>
 * Every decision in this class — is it live, may this person register, may they submit — is
 * taken against {@link Clock}, injected so tests can ask "what happens at exactly
 * {@code endAt}?" without waiting for it. Nothing here reads a timestamp from a request.
 *
 * <h2>A null viewer</h2>
 * Every contest endpoint currently requires a session, so {@code viewerId} is never null in
 * practice. The null branches below are not dead defensiveness for its own sake: they define
 * what a contest looks like to somebody with no account, which is what these methods would
 * have to return the day the catalogue is opened to anonymous browsing. Registration state
 * and per-problem progress are simply absent, rather than defaulting to something untrue.
 *
 * <h2>Drafts do not exist</h2>
 * A draft contest answers 404, not 403, for exactly the reason a draft problem does: 403
 * confirms that something is there. The filtering happens in the query
 * ({@link ContestRepository#findPublicContests}), so a draft never reaches this layer to be
 * accidentally serialised.
 */
@Service
public class ContestService {

    private static final Logger log = LoggerFactory.getLogger(ContestService.class);

    private final ContestRepository contestRepository;
    private final ContestParticipantRepository participantRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final Clock clock;

    public ContestService(ContestRepository contestRepository,
                          ContestParticipantRepository participantRepository,
                          SubmissionRepository submissionRepository,
                          UserRepository userRepository,
                          AuditService auditService,
                          com.codearena.system.BusinessMetrics metrics,
                          Clock clock) {
        this.contestRepository = contestRepository;
        this.participantRepository = participantRepository;
        this.submissionRepository = submissionRepository;
        this.metrics = metrics;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ browsing

    /**
     * The public catalogue.
     *
     * <p>{@code status} filters after the derived status is computed rather than in SQL,
     * because the status is not a column — it is a function of the schedule and the clock.
     * The set of non-draft contests is small enough that this is the right trade: the
     * alternative is encoding the time arithmetic into the query and having the rule live in
     * two places.
     */
    @Transactional(readOnly = true)
    public PageResponse<ContestSummaryResponse> list(ContestStatus filter, Pageable pageable, UUID viewerId) {
        Instant now = clock.instant();
        Page<Contest> page = contestRepository.findPublicContests(pageable);

        Set<UUID> registered = registeredContestIds(viewerId);
        List<ContestSummaryResponse> items = page.getContent().stream()
                .filter(contest -> filter == null || contest.statusAt(now) == filter)
                .map(contest -> summarise(contest, now, registered))
                .toList();

        return new PageResponse<>(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }

    private ContestSummaryResponse summarise(Contest contest, Instant now, Set<UUID> registered) {
        return ContestSummaryResponse.from(contest, now,
                participantRepository.countByContestPublicId(contest.getPublicId()),
                contest.getProblems().size(),
                registered.contains(contest.getPublicId()));
    }

    private Set<UUID> registeredContestIds(UUID viewerId) {
        if (viewerId == null) {
            return Set.of();
        }
        return contestRepository.findRegisteredFor(viewerId).stream()
                .map(Contest::getPublicId)
                .collect(Collectors.toSet());
    }

    /**
     * One contest, as the caller is entitled to see it.
     *
     * <p><b>The problem list is empty until the contest starts.</b> Publishing a contest
     * announces that it exists and when; it does not announce what is in it. Releasing the
     * problem set during UPCOMING would let a registered user read every statement in advance
     * and start solving before the clock does — which is not a minor leak, it is the contest.
     */
    @Transactional(readOnly = true)
    public ContestDetailResponse detail(UUID contestId, UUID viewerId) {
        Instant now = clock.instant();
        Contest contest = requireVisible(contestId);
        ContestStatus status = contest.statusAt(now);

        boolean registered = viewerId != null
                && participantRepository.isRegistered(contestId, viewerId);

        List<ContestProblemResponse> problems = status.hasStarted()
                ? problemsFor(contest, viewerId)
                : List.of();

        return new ContestDetailResponse(
                contest.getPublicId(), contest.getTitle(), contest.getSlug(),
                contest.getDescription(), status, contest.getStartAt(), contest.getEndAt(),
                now,
                participantRepository.countByContestPublicId(contestId),
                registered,
                registered && status.acceptsSubmissions(),
                problems);
    }

    /**
     * The contest's problems, with the caller's own progress.
     *
     * <p>Progress is fetched as two aggregate queries over the caller's submissions, not one
     * per problem: a twelve-problem contest should cost the same number of queries as a
     * one-problem contest.
     */
    private List<ContestProblemResponse> problemsFor(Contest contest, UUID viewerId) {
        Set<Long> solved = viewerId == null ? Set.of()
                : Set.copyOf(submissionRepository.findSolvedProblemIds(
                        contest.getPublicId(), viewerId, SubmissionStatus.ACCEPTED));

        Map<Long, Integer> attempts = viewerId == null ? Map.of()
                : submissionRepository.findAttemptCounts(
                                contest.getPublicId(), viewerId, penalisedVerdictNames())
                        .stream()
                        .collect(Collectors.toMap(
                                SubmissionRepository.ProblemAttempts::getProblemId,
                                SubmissionRepository.ProblemAttempts::getAttempts));

        return contest.getProblems().stream()
                .sorted(java.util.Comparator.comparingInt(ContestProblem::getDisplayOrder))
                .map(entry -> new ContestProblemResponse(
                        entry.getProblem().getPublicId(),
                        entry.getProblem().getSlug(),
                        entry.getProblem().getTitle(),
                        entry.label(),
                        entry.getDisplayOrder(),
                        entry.getPoints(),
                        viewerId == null ? null : solved.contains(entry.getProblem().getId()),
                        viewerId == null ? null : attempts.getOrDefault(entry.getProblem().getId(), 0)))
                .toList();
    }

    // ------------------------------------------------------------------ registration

    /**
     * Registers the caller.
     *
     * <p><b>Registration closes when the contest starts.</b> Letting someone join a contest
     * already in progress means they compete over a shorter window while the penalty clock
     * still runs from the contest's start, so their standings are not comparable with anyone
     * else's. Supporting it properly needs a per-participant start time and a different
     * penalty basis — a different product, not a looser check. ADR-033.
     *
     * <p><b>Idempotent.</b> A double-click, a retried request or two tabs must not produce
     * two registrations or an error the user cannot act on. The unique constraint is what
     * actually enforces this: the pre-check below is a fast path, and the
     * {@link DataIntegrityViolationException} catch is the real one, because two concurrent
     * requests can both pass a check and only one can win the index.
     */
    @Transactional
    public ContestRegistrationResponse register(UUID contestId, UUID userId) {
        Instant now = clock.instant();
        Contest contest = requireVisible(contestId);
        ContestStatus status = contest.statusAt(now);

        Optional<ContestParticipant> existing = participantRepository.findRegistration(contestId, userId);
        if (existing.isPresent()) {
            return new ContestRegistrationResponse(
                    contestId, status, existing.get().getRegisteredAt(), true);
        }

        if (!status.acceptsRegistration()) {
            throw new ConflictException("CONTEST_REGISTRATION_CLOSED",
                    status == ContestStatus.UPCOMING
                            ? "Registration is not open for this contest"
                            : "Registration for this contest closed when it started");
        }

        User user = userRepository.findByPublicId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));

        try {
            ContestParticipant participant = participantRepository.saveAndFlush(
                    ContestParticipant.of(contest, user));
            // In the registration transaction: eligibility to compete is exactly the kind
            // of fact a disputed result turns on.
            auditService.record(AuditAction.CONTEST_REGISTER, AuditOutcome.SUCCESS,
                    AuditEntityType.CONTEST, contestId.toString(),
                    AuditMetadata.of().put("contestSlug", contest.getSlug()).build());

            metrics.contestRegistration();
            log.info("event=CONTEST_REGISTERED contest={} user={}", contestId, userId);
            return new ContestRegistrationResponse(contestId, status, participant.getRegisteredAt(), false);
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent request for the same user. That is a success
            // from the caller's point of view: they are registered.
            log.info("event=CONTEST_REGISTRATION_RACE contest={} user={}", contestId, userId);
            return new ContestRegistrationResponse(contestId, status,
                    participantRepository.findRegistration(contestId, userId)
                            .map(ContestParticipant::getRegisteredAt).orElse(now),
                    true);
        }
    }

    // ------------------------------------------------------------------ shared

    /**
     * Loads a contest a normal user is allowed to see, or 404.
     *
     * <p>Drafts are indistinguishable from contests that do not exist. A 403 would confirm
     * that a contest is being prepared under that id, which is exactly what a draft is for
     * keeping quiet.
     */
    @Transactional(readOnly = true)
    public Contest requireVisible(UUID contestId) {
        return contestRepository.findForContestantView(contestId)
                .filter(contest -> contest.getLifecycle().isPubliclyVisible())
                .orElseThrow(() -> new ResourceNotFoundException("CONTEST_NOT_FOUND", "Contest not found"));
    }

    public Instant now() {
        return clock.instant();
    }

    /** The penalised verdicts as strings, for the native standings queries. */
    public static List<String> penalisedVerdictNames() {
        return ContestScoring.PENALISED_VERDICTS.stream().map(Enum::name).toList();
    }
}
