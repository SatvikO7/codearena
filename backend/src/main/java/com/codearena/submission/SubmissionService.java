package com.codearena.submission;

import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.common.ConflictException;
import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.system.BusinessMetrics;
import com.codearena.common.ValidationException;
import com.codearena.contest.Contest;
import com.codearena.contest.ContestParticipantRepository;
import com.codearena.contest.ContestProblem;
import com.codearena.contest.ContestProblemRepository;
import com.codearena.contest.ContestRepository;
import com.codearena.contest.ContestStatus;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemStatus;
import com.codearena.queue.SubmissionQueuePublisher;
import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.dto.SubmissionAcceptedResponse;
import com.codearena.submission.dto.SubmissionDetailResponse;
import com.codearena.submission.dto.SubmissionRequest;
import com.codearena.submission.dto.SubmissionStatusResponse;
import com.codearena.submission.dto.SubmissionSummaryResponse;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepting submissions and serving them back.
 *
 * <p>Creation does three things and returns: validate, persist, ask for publication. It
 * never waits for a judge. The HTTP request is finished long before any container starts,
 * which is the entire reason for the queue.
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SubmissionRepository submissionRepository;
    private final SubmissionTestResultRepository testResultRepository;
    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ContestParticipantRepository participantRepository;
    private final ContestProblemRepository contestProblemRepository;
    private final ContestRepository contestRepository;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final Clock clock;
    private final int maxSourceBytes;

    public SubmissionService(SubmissionRepository submissionRepository,
                             SubmissionTestResultRepository testResultRepository,
                             ProblemRepository problemRepository,
                             UserRepository userRepository,
                             ApplicationEventPublisher eventPublisher,
                             ContestParticipantRepository participantRepository,
                             ContestProblemRepository contestProblemRepository,
                             ContestRepository contestRepository,
                             AuditService auditService,
                             BusinessMetrics metrics,
                             Clock clock,
                             @Value("${codearena.submission.max-source-bytes:65536}") int maxSourceBytes) {
        this.submissionRepository = submissionRepository;
        this.testResultRepository = testResultRepository;
        this.problemRepository = problemRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.participantRepository = participantRepository;
        this.contestProblemRepository = contestProblemRepository;
        this.contestRepository = contestRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
        this.maxSourceBytes = maxSourceBytes;
    }

    /**
     * Accepts a submission for judging.
     *
     * <p>The problem must be PUBLISHED. A draft or archived problem answers 404 rather than
     * 403, matching the catalogue: a user who cannot see a problem must not be able to
     * confirm it exists by trying to submit to it.
     *
     * <p>The insert and the "this needs queueing" fact are the same row, committed
     * together. Publication is requested through an event that fires only after that commit
     * — see {@link SubmissionQueuePublisher} for why the ordering is not negotiable.
     */
    @Transactional
    public SubmissionAcceptedResponse submit(UUID problemPublicId, SubmissionRequest request, UUID authorPublicId) {
        Problem problem = problemRepository.findByPublicId(problemPublicId)
                .filter(candidate -> candidate.getStatus() == ProblemStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("PROBLEM_NOT_FOUND", "Problem not found"));

        // Null contest: this is practice. Contest submissions go through submitToContest,
        // which adds the eligibility checks and then lands in the same persist() below.
        Submission submission = persist(problem, request, authorPublicId, null);

        // Language and size are safe to log; the source itself never is.
        log.info("event=SUBMISSION_CREATED submission={} problem={} user={} language={}",
                submission.getPublicId(), problem.getPublicId(), authorPublicId,
                submission.getLanguage());

        return new SubmissionAcceptedResponse(
                submission.getPublicId(), submission.getStatus(), submission.getCreatedAt());
    }

    /**
     * Accepts a submission made inside a contest.
     *
     * <h2>Five things are checked, and none of them are taken from the request</h2>
     * <ol>
     *   <li><b>The contest exists and is visible.</b> A draft answers 404.</li>
     *   <li><b>The problem belongs to this contest.</b> Verified by looking up the
     *       association in the database. This is what stops a contestant submitting to any
     *       problem they happen to know the id of by pairing it with a contest id — the
     *       relationship is read, never inferred from the two ids agreeing in a URL.</li>
     *   <li><b>The caller is registered.</b> Being authenticated is not enough.</li>
     *   <li><b>The contest is LIVE right now</b>, by the server's clock.</li>
     *   <li><b>The problem is still PUBLISHED.</b></li>
     * </ol>
     *
     * <h2>The deadline</h2>
     * Evaluated here, against {@link Clock}, on the half-open window
     * {@code [startAt, endAt)}. A browser still showing a running countdown — because its
     * clock drifted, because the tab was asleep, or because somebody set it back — is
     * refused all the same. The countdown is a convenience; this check is the contest.
     *
     * <p>The instant recorded against the submission is the database's, through the same
     * auditing that stamps every other submission. No timestamp from a client is read
     * anywhere in this method, and none could be: {@code SubmissionRequest} has no field
     * for one.
     */
    @Transactional
    public SubmissionAcceptedResponse submitToContest(UUID contestPublicId,
                                                      UUID problemPublicId,
                                                      SubmissionRequest request,
                                                      UUID authorPublicId) {
        Instant now = clock.instant();

        Contest contest = contestRepository.findByPublicId(contestPublicId)
                .filter(candidate -> candidate.getLifecycle().isPubliclyVisible())
                .orElseThrow(() -> new ResourceNotFoundException("CONTEST_NOT_FOUND", "Contest not found"));

        // The problem must be in THIS contest. A problem that is not answers 404: the
        // caller is not entitled to learn whether it exists elsewhere.
        ContestProblem entry = contestProblemRepository.findEntry(contestPublicId, problemPublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CONTEST_PROBLEM_NOT_FOUND", "This problem is not part of this contest"));

        if (!participantRepository.isRegistered(contestPublicId, authorPublicId)) {
            throw new ConflictException("NOT_REGISTERED",
                    "You are not registered for this contest");
        }

        ContestStatus status = contest.statusAt(now);
        if (!status.acceptsSubmissions()) {
            // Names the state rather than saying "closed": a contestant who submits four
            // seconds late deserves to be told which side of the boundary they landed on.
            log.info("event=CONTEST_SUBMISSION_REJECTED contest={} user={} status={}",
                    contestPublicId, authorPublicId, status);
            throw new ConflictException("CONTEST_NOT_LIVE", switch (status) {
                case UPCOMING -> "This contest has not started yet";
                case ENDED -> "This contest has ended";
                case CANCELLED -> "This contest was cancelled";
                default -> "This contest is not accepting submissions";
            });
        }

        Problem problem = entry.getProblem();
        if (problem.getStatus() != ProblemStatus.PUBLISHED) {
            // A problem unpublished mid-contest. Not the contestant's fault, and not
            // something to answer with a confusing 404 about the contest.
            throw new ConflictException("PROBLEM_UNAVAILABLE",
                    "This problem is temporarily unavailable");
        }

        Submission submission = persist(problem, request, authorPublicId, contest);

        log.info("event=CONTEST_SUBMISSION_CREATED submission={} contest={} problem={} user={} language={}",
                submission.getPublicId(), contestPublicId, problemPublicId,
                authorPublicId, submission.getLanguage());

        return new SubmissionAcceptedResponse(
                submission.getPublicId(), submission.getStatus(), submission.getCreatedAt());
    }

    /**
     * Persists a submission and asks for it to be queued.
     *
     * <p>Shared by the practice and contest paths on purpose: one place that writes a
     * submission row, one place that publishes the queue event, and therefore one set of
     * outbox semantics to get right rather than two that can drift.
     */
    private Submission persist(Problem problem, SubmissionRequest request,
                               UUID authorPublicId, Contest contest) {
        String source = request.sourceCode();
        // Measured in bytes, not characters: a program of multi-byte identifiers hits
        // the real storage and transfer cost long before it looks long.
        int sourceBytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes > maxSourceBytes) {
            throw new ValidationException("sourceCode",
                    "Source code must be at most %d bytes (received %d)".formatted(maxSourceBytes, sourceBytes));
        }

        User author = userRepository.findByPublicId(authorPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));

        Submission submission = submissionRepository.saveAndFlush(
                Submission.queue(problem, author, request.language(), source, contest));

        eventPublisher.publishEvent(
                new SubmissionQueuePublisher.SubmissionCreatedEvent(submission.getPublicId()));

        // Recorded here rather than in each caller, so the practice and contest paths
        // cannot drift apart. The event says that somebody submitted, never WHAT they
        // submitted: the source code is the one thing that must never reach an audit row.
        auditService.record(AuditAction.SUBMISSION_CREATE, AuditOutcome.SUCCESS,
                AuditEntityType.SUBMISSION, submission.getPublicId().toString(),
                AuditMetadata.of()
                        .put("problemId", problem.getPublicId())
                        .put("language", request.language())
                        .put("sourceBytes", sourceBytes)
                        .put("contestId", contest == null ? null : contest.getPublicId())
                        .build());

        // Counted at acceptance, where the worker counts verdicts. The gap between the
        // two is the most diagnostic number the system produces: a judge that has
        // stopped shows up as accepted climbing while judged does not, which no amount
        // of HTTP metrics would reveal -- every one of those requests succeeded.
        metrics.submissionAccepted(request.language(), contest == null ? "practice" : "contest");

        return submission;
    }
    /**
     * Reads one submission.
     *
     * <p>Ownership is checked here, in the only place that serves a submission, and a
     * submission belonging to somebody else answers 404 rather than 403. Distinguishing the
     * two would turn this endpoint into an oracle for which submission ids exist.
     * Administrators may read any submission, which is the existing role model rather than
     * a new one.
     */
    @Transactional(readOnly = true)
    public SubmissionDetailResponse get(UUID submissionPublicId, UUID viewerPublicId, Role viewerRole) {
        Submission submission = submissionRepository.findByPublicId(submissionPublicId)
                .orElseThrow(SubmissionService::notFound);

        boolean owner = submission.getUser().getPublicId().equals(viewerPublicId);
        if (!owner && viewerRole != Role.ADMIN) {
            log.info("event=SUBMISSION_ACCESS_DENIED submission={} viewer={}", submissionPublicId, viewerPublicId);
            throw notFound();
        }
        return SubmissionDetailResponse.from(
                submission, testResultRepository.findBySubmissionIdOrderByPositionAsc(submission.getId()));
    }

    /**
     * Confirms the caller may watch this submission, for the SSE endpoint.
     *
     * <p>Authorisation is the same rule as {@link #get}, evaluated once when the stream is
     * opened. It has to be: an event channel that skipped the ownership check would be a
     * second, weaker door to the same data.
     */
    @Transactional(readOnly = true)
    public SubmissionStatusResponse requireReadableStatus(UUID submissionPublicId,
                                                          UUID viewerPublicId,
                                                          Role viewerRole) {
        Submission submission = submissionRepository.findByPublicId(submissionPublicId)
                .orElseThrow(SubmissionService::notFound);

        boolean owner = submission.getUser().getPublicId().equals(viewerPublicId);
        if (!owner && viewerRole != Role.ADMIN) {
            log.info("event=SUBMISSION_STREAM_DENIED submission={} viewer={}",
                    submissionPublicId, viewerPublicId);
            throw notFound();
        }
        return SubmissionStatusResponse.from(submission);
    }

    /**
     * The current state, for pushing over an already-authorised stream.
     *
     * <p>No ownership check, and that is safe only because it is unreachable from HTTP: the
     * single caller is the event subscriber, which sends the result exclusively to
     * connections that passed {@link #requireReadableStatus} when they opened.
     */
    @Transactional(readOnly = true)
    public Optional<SubmissionStatusResponse> findStatus(UUID submissionPublicId) {
        return submissionRepository.findByPublicId(submissionPublicId)
                .map(SubmissionStatusResponse::from);
    }

    /**
     * One user's own history.
     *
     * <p>The viewer's id is a query parameter, not a filter applied afterwards, so there is
     * no arrangement of request parameters that widens this beyond the caller's own rows.
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionSummaryResponse> listOwn(UUID viewerPublicId,
                                                           UUID problemPublicId,
                                                           SubmissionStatus status,
                                                           Language language,
                                                           Pageable pageable) {
        User viewer = userRepository.findByPublicId(viewerPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));

        // An unknown problem resolves to a sentinel that matches nothing, rather than to
        // null. Null would mean "no filter", so a mistyped problem id would silently widen
        // the result to the user's entire history instead of returning nothing.
        Long problemId = problemPublicId == null
                ? null
                : problemRepository.findByPublicId(problemPublicId).map(Problem::getId).orElse(-1L);

        Page<SubmissionSummaryProjection> page = submissionRepository.findSummariesForUser(
                viewer.getId(), problemId, status, language, pageable);
        return PageResponse.from(page, SubmissionSummaryResponse::from);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("SUBMISSION_NOT_FOUND", "Submission not found");
    }
}
