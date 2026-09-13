package com.codearena.submission;

import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.common.ValidationException;
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
    private final int maxSourceBytes;

    public SubmissionService(SubmissionRepository submissionRepository,
                             SubmissionTestResultRepository testResultRepository,
                             ProblemRepository problemRepository,
                             UserRepository userRepository,
                             ApplicationEventPublisher eventPublisher,
                             @Value("${codearena.submission.max-source-bytes:65536}") int maxSourceBytes) {
        this.submissionRepository = submissionRepository;
        this.testResultRepository = testResultRepository;
        this.problemRepository = problemRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
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
        String source = request.sourceCode();
        int sourceBytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes > maxSourceBytes) {
            // Measured in bytes, not characters: a program of multi-byte identifiers hits
            // the real storage and transfer cost long before it looks long.
            throw new ValidationException("sourceCode",
                    "Source code must be at most %d bytes (received %d)".formatted(maxSourceBytes, sourceBytes));
        }

        Problem problem = problemRepository.findByPublicId(problemPublicId)
                .filter(candidate -> candidate.getStatus() == ProblemStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("PROBLEM_NOT_FOUND", "Problem not found"));

        User author = userRepository.findByPublicId(authorPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));

        Submission submission = submissionRepository.saveAndFlush(
                Submission.queue(problem, author, request.language(), source));

        eventPublisher.publishEvent(
                new SubmissionQueuePublisher.SubmissionCreatedEvent(submission.getPublicId()));

        // Language and size are safe to log; the source itself never is.
        log.info("event=SUBMISSION_CREATED submission={} problem={} user={} language={} sourceBytes={}",
                submission.getPublicId(), problem.getPublicId(), author.getPublicId(),
                submission.getLanguage(), sourceBytes);

        return new SubmissionAcceptedResponse(
                submission.getPublicId(), submission.getStatus(), submission.getCreatedAt());
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
