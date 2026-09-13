package com.codearena.contest;

import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.common.ConflictException;
import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.common.ValidationException;
import com.codearena.contest.dto.ContestDetailResponse;
import com.codearena.contest.dto.ContestParticipantResponse;
import com.codearena.contest.dto.ContestProblemRequest;
import com.codearena.contest.dto.ContestProblemResponse;
import com.codearena.contest.dto.ContestProblemUpdateRequest;
import com.codearena.contest.dto.ContestRequest;
import com.codearena.contest.dto.ContestSummaryResponse;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemStatus;
import com.codearena.submission.SubmissionRepository;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Contest authoring and management. Administrators only.
 *
 * <h2>The rule that runs through all of this</h2>
 * A contest is freely editable while it is a DRAFT or UPCOMING, and frozen the moment it
 * goes LIVE. The freeze is enforced by {@link Contest#requireEditable} on the aggregate
 * rather than here, so a new endpoint added later cannot forget it.
 *
 * <p>There is no emergency override for a live contest. Changing what a problem is worth
 * mid-contest silently rewrites the standings of everyone who already solved it; moving the
 * end time invalidates every penalty already computed. An administrator who genuinely must
 * stop a contest cancels it, which is visible to every contestant, rather than editing it,
 * which is not.
 */
@Service
public class ContestAdminService {

    private static final Logger log = LoggerFactory.getLogger(ContestAdminService.class);

    private final ContestRepository contestRepository;
    private final ContestProblemRepository contestProblemRepository;
    private final ContestParticipantRepository participantRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ContestAdminService(ContestRepository contestRepository,
                               ContestProblemRepository contestProblemRepository,
                               ContestParticipantRepository participantRepository,
                               ProblemRepository problemRepository,
                               SubmissionRepository submissionRepository,
                               UserRepository userRepository,
                               AuditService auditService,
                               Clock clock) {
        this.contestRepository = contestRepository;
        this.contestProblemRepository = contestProblemRepository;
        this.participantRepository = participantRepository;
        this.problemRepository = problemRepository;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authoring

    @Transactional
    public ContestDetailResponse create(ContestRequest request, UUID adminPublicId) {
        if (contestRepository.existsBySlugIgnoreCase(request.slug())) {
            throw new ConflictException("CONTEST_SLUG_TAKEN",
                    "A contest with this slug already exists");
        }
        User admin = userRepository.findByPublicId(adminPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));

        // The aggregate validates the schedule; a bad one throws before anything is written.
        Contest contest = contestRepository.saveAndFlush(Contest.create(
                request.title(), request.slug(), request.description(),
                request.startAt(), request.endAt(), admin));

        // In this transaction: a contest cannot exist without the record of who created it.
        auditService.record(AuditAction.CONTEST_CREATE, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contest.getPublicId().toString(),
                AuditMetadata.of()
                        .put("slug", contest.getSlug())
                        .put("title", contest.getTitle())
                        .put("startAt", contest.getStartAt())
                        .put("endAt", contest.getEndAt())
                        .build());

        log.info("event=CONTEST_CREATED contest={} slug={} startAt={} endAt={} by={}",
                contest.getPublicId(), contest.getSlug(),
                contest.getStartAt(), contest.getEndAt(), adminPublicId);

        return detail(contest.getPublicId());
    }

    @Transactional
    public ContestDetailResponse update(UUID contestId, ContestRequest request) {
        Instant now = clock.instant();
        Contest contest = require(contestId);

        if (contestRepository.existsBySlugIgnoreCaseAndPublicIdNot(request.slug(), contestId)) {
            throw new ConflictException("CONTEST_SLUG_TAKEN",
                    "A contest with this slug already exists");
        }

        contest.updateDetails(request.title(), request.slug(), request.description(), now);
        contest.reschedule(request.startAt(), request.endAt(), now);

        auditService.record(AuditAction.CONTEST_UPDATE, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("slug", contest.getSlug())
                        .put("startAt", contest.getStartAt())
                        .put("endAt", contest.getEndAt())
                        .build());

        log.info("event=CONTEST_UPDATED contest={} startAt={} endAt={}",
                contestId, contest.getStartAt(), contest.getEndAt());
        return detail(contestId);
    }

    @Transactional
    public ContestDetailResponse publish(UUID contestId) {
        Contest contest = require(contestId);
        ContestStatus previous = contest.statusAt(clock.instant());
        contest.publish(clock.instant());

        auditService.record(AuditAction.CONTEST_PUBLISH, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("slug", contest.getSlug())
                        .put("problemCount", contest.getProblems().size())
                        .transition(previous, contest.statusAt(clock.instant()))
                        .build());

        log.info("event=CONTEST_PUBLISHED contest={} startAt={}", contestId, contest.getStartAt());
        return detail(contestId);
    }

    @Transactional
    public ContestDetailResponse cancel(UUID contestId) {
        Contest contest = require(contestId);
        ContestStatus before = contest.statusAt(clock.instant());
        contest.cancel(clock.instant());
        // Worth a WARN: cancelling a contest people are competing in is a significant event
        // and somebody reading the logs afterwards should not have to hunt for it.
        // The most consequential administrative act in the system: stopping a contest
        // people may be competing in. The participant count is recorded because it is what
        // makes the decision significant.
        auditService.record(AuditAction.CONTEST_CANCEL, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("slug", contest.getSlug())
                        .put("participants", participantRepository.countByContestPublicId(contestId))
                        .transition(before, ContestStatus.CANCELLED)
                        .build());

        log.warn("event=CONTEST_CANCELLED contest={} previousStatus={} participants={}",
                contestId, before, participantRepository.countByContestPublicId(contestId));
        return detail(contestId);
    }

    /**
     * Deletes a contest.
     *
     * <p>Permitted only for an untouched draft: never published, nobody registered, nothing
     * submitted. Anything else is history — a contest people entered and competed in is a
     * record of what they did, and removing it would take their submissions with it or
     * orphan them.
     *
     * <p>The database agrees independently: {@code fk_submissions_contest} is ON DELETE
     * RESTRICT, so even a mistake here cannot destroy submissions.
     */
    @Transactional
    public void delete(UUID contestId) {
        Contest contest = require(contestId);

        if (contest.getLifecycle() != ContestLifecycle.DRAFT) {
            throw new ConflictException("CONTEST_NOT_DELETABLE",
                    "Only a draft contest can be deleted. Cancel this one instead.");
        }
        if (participantRepository.countByContestPublicId(contestId) > 0) {
            throw new ConflictException("CONTEST_NOT_DELETABLE",
                    "This contest has participants and cannot be deleted");
        }
        if (submissionRepository.existsByContestPublicId(contestId)) {
            throw new ConflictException("CONTEST_NOT_DELETABLE",
                    "This contest has submissions and cannot be deleted");
        }

        // Recorded before the delete, while the contest can still be described. The row
        // outlives the contest deliberately: entity_id is text rather than a foreign key,
        // so the record of a deletion is not itself deleted by it.
        auditService.record(AuditAction.CONTEST_DELETE, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("slug", contest.getSlug())
                        .put("title", contest.getTitle())
                        .build());

        contestRepository.delete(contest);
        log.info("event=CONTEST_DELETED contest={}", contestId);
    }

    // ------------------------------------------------------------------ problems

    /**
     * Adds a published problem to the contest.
     *
     * <p>The problem must be PUBLISHED: a contest cannot be built on a draft that might
     * still change, or on an archived problem nobody can open.
     */
    @Transactional
    public ContestDetailResponse addProblem(UUID contestId, ContestProblemRequest request) {
        Instant now = clock.instant();
        Contest contest = require(contestId);
        contest.requireEditable(now);

        Problem problem = problemRepository.findByPublicId(request.problemId())
                .orElseThrow(() -> new ResourceNotFoundException("PROBLEM_NOT_FOUND", "Problem not found"));
        if (problem.getStatus() != ProblemStatus.PUBLISHED) {
            throw new ValidationException("problemId",
                    "Only a published problem can be added to a contest");
        }
        if (contestProblemRepository.existsByContestPublicIdAndProblemPublicId(contestId, request.problemId())) {
            throw new ConflictException("CONTEST_PROBLEM_DUPLICATE",
                    "This problem is already in the contest");
        }

        int nextOrder = contestProblemRepository.highestDisplayOrder(contest.getId()) + 1;
        contest.addProblem(
                ContestProblem.of(contest, problem, nextOrder, request.pointsOrDefault()), now);
        contestRepository.saveAndFlush(contest);

        auditService.record(AuditAction.CONTEST_PROBLEM_ADD, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("problemId", request.problemId())
                        .put("problemSlug", problem.getSlug())
                        .put("displayOrder", nextOrder)
                        .put("points", request.pointsOrDefault())
                        .build());

        log.info("event=CONTEST_PROBLEM_ADDED contest={} problem={} order={} points={}",
                contestId, request.problemId(), nextOrder, request.pointsOrDefault());
        return detail(contestId);
    }

    @Transactional
    public ContestDetailResponse updateProblem(UUID contestId, UUID problemId,
                                               ContestProblemUpdateRequest request) {
        Instant now = clock.instant();
        Contest contest = require(contestId);
        contest.requireEditable(now);

        ContestProblem entry = contestProblemRepository.findEntry(contestId, problemId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CONTEST_PROBLEM_NOT_FOUND", "This problem is not part of this contest"));

        int previousPoints = entry.getPoints();
        int previousOrder = entry.getDisplayOrder();

        if (request.points() != null) {
            entry.repoint(request.points());
        }
        if (request.displayOrder() != null && request.displayOrder() != entry.getDisplayOrder()) {
            reorder(contest, entry, request.displayOrder());
        }
        contestRepository.saveAndFlush(contest);

        // Points and ordering decide what the contest is worth, so a change to either is
        // recorded with both the old and new values.
        auditService.record(AuditAction.CONTEST_PROBLEM_UPDATE, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("problemId", problemId)
                        .put("previousPoints", previousPoints)
                        .put("points", entry.getPoints())
                        .put("previousDisplayOrder", previousOrder)
                        .put("displayOrder", entry.getDisplayOrder())
                        .build());

        log.info("event=CONTEST_PROBLEM_UPDATED contest={} problem={} points={} order={}",
                contestId, problemId, entry.getPoints(), entry.getDisplayOrder());
        return detail(contestId);
    }

    /**
     * Moves a problem to a new position, shifting the others to close and open a gap.
     *
     * <p>Done by renumbering the whole list rather than swapping two rows, because
     * {@code uq_contest_problems_order} means two problems cannot briefly share a slot. The
     * entries are moved to a temporary range first for the same reason: assigning final
     * positions one at a time would collide with a row that has not been moved yet.
     */
    private void reorder(Contest contest, ContestProblem moving, int target) {
        List<ContestProblem> ordered = contest.getProblems().stream()
                .sorted(Comparator.comparingInt(ContestProblem::getDisplayOrder))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        ordered.remove(moving);
        int bounded = Math.max(0, Math.min(target, ordered.size()));
        ordered.add(bounded, moving);

        // Park everything out of the way so no intermediate assignment collides with a
        // position still held by another row.
        int parking = contestProblemRepository.highestDisplayOrder(contest.getId()) + 1000;
        for (ContestProblem entry : ordered) {
            entry.moveTo(parking++);
        }
        contestRepository.saveAndFlush(contest);

        for (int position = 0; position < ordered.size(); position++) {
            ordered.get(position).moveTo(position);
        }
    }

    @Transactional
    public ContestDetailResponse removeProblem(UUID contestId, UUID problemId) {
        Instant now = clock.instant();
        Contest contest = require(contestId);
        contest.requireEditable(now);

        ContestProblem entry = contestProblemRepository.findEntry(contestId, problemId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CONTEST_PROBLEM_NOT_FOUND", "This problem is not part of this contest"));

        contest.removeProblem(entry, now);
        contestRepository.saveAndFlush(contest);

        // Close the gap, so positions stay contiguous and labels stay A, B, C.
        List<ContestProblem> remaining = contest.getProblems().stream()
                .sorted(Comparator.comparingInt(ContestProblem::getDisplayOrder))
                .toList();
        for (int position = 0; position < remaining.size(); position++) {
            remaining.get(position).moveTo(position);
        }
        contestRepository.saveAndFlush(contest);

        auditService.record(AuditAction.CONTEST_PROBLEM_REMOVE, AuditOutcome.SUCCESS,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of().put("problemId", problemId).build());

        log.info("event=CONTEST_PROBLEM_REMOVED contest={} problem={}", contestId, problemId);
        return detail(contestId);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ContestSummaryResponse> list(Pageable pageable) {
        Instant now = clock.instant();
        Page<Contest> page = contestRepository.findAllForAdmin(pageable);
        return PageResponse.from(page, contest -> ContestSummaryResponse.from(
                contest, now,
                participantRepository.countByContestPublicId(contest.getPublicId()),
                contest.getProblems().size(),
                false));
    }

    @Transactional(readOnly = true)
    public ContestDetailResponse detail(UUID contestId) {
        Instant now = clock.instant();
        Contest contest = require(contestId);

        // Administrators see the problem set at every stage, including before the contest
        // starts: they are the people who built it.
        return new ContestDetailResponse(
                contest.getPublicId(), contest.getTitle(), contest.getSlug(),
                contest.getDescription(), contest.statusAt(now),
                contest.getStartAt(), contest.getEndAt(), now,
                participantRepository.countByContestPublicId(contestId),
                false, false,
                problems(contestId));
    }

    @Transactional(readOnly = true)
    public List<ContestParticipantResponse> participants(UUID contestId) {
        require(contestId);
        return participantRepository.findForAdmin(contestId).stream()
                .map(participant -> new ContestParticipantResponse(
                        participant.getUser().getPublicId(),
                        participant.getUser().getUsername(),
                        participant.getRegisteredAt()))
                .toList();
    }

    private List<ContestProblemResponse> problems(UUID contestId) {
        Contest contest = contestRepository.findForContestantView(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("CONTEST_NOT_FOUND", "Contest not found"));
        return contest.getProblems().stream()
                .sorted(Comparator.comparingInt(ContestProblem::getDisplayOrder))
                .map(entry -> new ContestProblemResponse(
                        entry.getProblem().getPublicId(),
                        entry.getProblem().getSlug(),
                        entry.getProblem().getTitle(),
                        entry.label(),
                        entry.getDisplayOrder(),
                        entry.getPoints(),
                        null, null))
                .toList();
    }

    private Contest require(UUID contestId) {
        return contestRepository.findWithProblemsByPublicId(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("CONTEST_NOT_FOUND", "Contest not found"));
    }
}
