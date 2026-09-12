package com.codearena.problem;

import com.codearena.common.ConflictException;
import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.common.ValidationException;
import com.codearena.problem.dto.AdminProblemDetailResponse;
import com.codearena.problem.dto.ProblemRequest;
import com.codearena.problem.dto.ProblemSummaryResponse;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Problem authoring and lifecycle, for administrators.
 *
 * <p>Authorisation is not decided here. The {@code /api/admin/**} rule established in
 * Phase 2 requires the ADMIN role before a request reaches any of this code, and the
 * controller carries a method-level check as a second gate. This service assumes the
 * caller is entitled and concerns itself with domain correctness.
 *
 * <p>Every mutation records who performed it through the problem's {@code createdBy} and
 * {@code updatedBy} relationships, and emits a structured log line naming the actor,
 * the action and the problem. See ADR-016 for why that is the audit mechanism at this
 * stage rather than a dedicated audit table.
 */
@Service
@Transactional
public class ProblemAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdminService.class);

    public static final String SLUG_TAKEN = "PROBLEM_SLUG_ALREADY_EXISTS";

    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;

    public ProblemAdminService(ProblemRepository problemRepository, UserRepository userRepository) {
        this.problemRepository = problemRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PageResponse<ProblemSummaryResponse> list(ProblemStatus status,
                                                     Difficulty difficulty,
                                                     String search,
                                                     Pageable pageable) {
        Page<Problem> page = problemRepository.findForAdmin(
                status, difficulty, searchPattern(search), pageable);
        return PageResponse.from(page, ProblemSummaryResponse::forAdmin);
    }

    @Transactional(readOnly = true)
    public AdminProblemDetailResponse get(UUID problemId) {
        return AdminProblemDetailResponse.from(require(problemId));
    }

    // ----------------------------------------------------------------- mutations

    public AdminProblemDetailResponse create(ProblemRequest request, UUID actorPublicId) {
        User actor = requireActor(actorPublicId);
        String slug = resolveSlugForCreate(request);

        // Pre-check for a friendly error; the unique constraint below is what actually
        // guarantees it, since a concurrent create can claim the slug in between.
        if (problemRepository.existsBySlug(slug)) {
            throw slugConflict(slug);
        }

        Problem problem = Problem.createDraft(slug, request.title().trim(), request.difficulty(), actor);
        applyContent(problem, request, actor);

        Problem saved = persist(problem, slug);
        log.info("admin action=PROBLEM_CREATED actor={} problem={} slug={}",
                actor.getUsername(), saved.getPublicId(), saved.getSlug());
        return AdminProblemDetailResponse.from(saved);
    }

    public AdminProblemDetailResponse update(UUID problemId, ProblemRequest request, UUID actorPublicId) {
        User actor = requireActor(actorPublicId);
        Problem problem = require(problemId);

        // A slug is changed only when one is explicitly supplied. Omitting it leaves the
        // existing handle alone, so renaming the title cannot quietly break every link
        // that points at this problem.
        if (request.slug() != null && !request.slug().isBlank()) {
            String slug = normaliseSlug(request.slug());
            if (!slug.equals(problem.getSlug())) {
                if (problemRepository.existsBySlugAndIdNot(slug, problem.getId())) {
                    throw slugConflict(slug);
                }
                problem.changeSlug(slug);
            }
        }

        applyContent(problem, request, actor);

        Problem saved = persist(problem, problem.getSlug());
        log.info("admin action=PROBLEM_UPDATED actor={} problem={} status={}",
                actor.getUsername(), saved.getPublicId(), saved.getStatus());
        return AdminProblemDetailResponse.from(saved);
    }

    // ----------------------------------------------------------------- lifecycle

    public AdminProblemDetailResponse publish(UUID problemId, UUID actorPublicId) {
        return transition(problemId, actorPublicId, "PROBLEM_PUBLISHED", Problem::publish);
    }

    public AdminProblemDetailResponse unpublish(UUID problemId, UUID actorPublicId) {
        return transition(problemId, actorPublicId, "PROBLEM_UNPUBLISHED", Problem::unpublish);
    }

    public AdminProblemDetailResponse archive(UUID problemId, UUID actorPublicId) {
        return transition(problemId, actorPublicId, "PROBLEM_ARCHIVED", Problem::archive);
    }

    public AdminProblemDetailResponse restore(UUID problemId, UUID actorPublicId) {
        return transition(problemId, actorPublicId, "PROBLEM_RESTORED", Problem::restoreToDraft);
    }

    private AdminProblemDetailResponse transition(UUID problemId, UUID actorPublicId,
                                                  String action, LifecycleOperation operation) {
        User actor = requireActor(actorPublicId);
        Problem problem = require(problemId);

        // The entity enforces which moves are legal and, for publication, whether the
        // content is complete. This method only records who asked.
        operation.apply(problem, actor);

        log.info("admin action={} actor={} problem={} status={}",
                action, actor.getUsername(), problem.getPublicId(), problem.getStatus());
        return AdminProblemDetailResponse.from(problem);
    }

    @FunctionalInterface
    private interface LifecycleOperation {
        void apply(Problem problem, User actor);
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Applies an update payload to the problem.
     *
     * <p>One rule, applied to every field: <strong>omitted means unchanged; an explicit
     * empty value clears.</strong> Send {@code ""} to erase a statement, {@code []} to
     * remove every test case.
     *
     * <p>This is PATCH-like semantics on a PUT, chosen deliberately over strict "replace
     * the whole resource". Strict replacement would mean a client that omits
     * {@code testCases} silently destroys a problem's entire answer key — expensive
     * authoring data, deleted by an absent field. The failure mode of the rule used here
     * is that a caller who wanted to clear something must say so explicitly, which is
     * noisy but recoverable; the failure mode of the alternative is irreversible data
     * loss. The asymmetry that previously existed, where scalars were cleared but
     * collections preserved, was worse than either: the behaviour depended on the field.
     */
    private void applyContent(Problem problem, ProblemRequest request, User actor) {
        Set<ProblemTag> tags = request.tags() == null
                ? EnumSet.copyOf(problem.getTags().isEmpty()
                        ? EnumSet.noneOf(ProblemTag.class) : problem.getTags())
                : (request.tags().isEmpty()
                        ? EnumSet.noneOf(ProblemTag.class) : EnumSet.copyOf(request.tags()));

        problem.updateContent(
                request.title().trim(),
                resolveContent(request.statement(), problem.getStatement()),
                resolveContent(request.inputFormat(), problem.getInputFormat()),
                resolveContent(request.outputFormat(), problem.getOutputFormat()),
                resolveContent(request.constraints(), problem.getConstraints()),
                resolveContent(request.explanation(), problem.getExplanation()),
                request.difficulty(),
                request.timeLimitMs() == null ? problem.getTimeLimitMs() : request.timeLimitMs(),
                request.memoryLimitMb() == null ? problem.getMemoryLimitMb() : request.memoryLimitMb(),
                tags,
                actor);

        if (request.examples() != null) {
            problem.replaceExamples(request.examples().stream()
                    .map(e -> new Problem.ExampleContent(e.input(), e.output(), e.explanation()))
                    .toList());
        }
        if (request.testCases() != null) {
            problem.replaceTestCases(request.testCases().stream()
                    .map(t -> new Problem.TestCaseContent(
                            t.input(), t.expectedOutput(), t.hiddenOrDefault(), t.weightOrDefault()))
                    .toList());
        }
    }

    private Problem persist(Problem problem, String slug) {
        try {
            return problemRepository.saveAndFlush(problem);
        } catch (DataIntegrityViolationException e) {
            // Lost the race to a concurrent write. Translate the constraint violation into
            // the same 409 the pre-check would have produced rather than a 500.
            if (describe(e).contains("uq_problems_slug")) {
                throw slugConflict(slug);
            }
            throw e;
        }
    }

    private String resolveSlugForCreate(ProblemRequest request) {
        if (request.slug() != null && !request.slug().isBlank()) {
            return normaliseSlug(request.slug());
        }
        String derived = Slugs.from(request.title());
        if (derived.isEmpty()) {
            throw new ValidationException("slug",
                    "A slug could not be derived from the title; supply one explicitly");
        }
        return derived;
    }

    private String normaliseSlug(String slug) {
        String normalised = slug.trim().toLowerCase(Locale.ROOT);
        if (!Slugs.isValid(normalised)) {
            throw new ValidationException("slug",
                    "Slug must be lower-case words separated by single hyphens");
        }
        return normalised;
    }

    private ConflictException slugConflict(String slug) {
        return new ConflictException(SLUG_TAKEN, "A problem with the slug '" + slug + "' already exists");
    }

    private Problem require(UUID problemId) {
        return problemRepository.findWithDetailByPublicId(problemId)
                .orElseThrow(() -> new ResourceNotFoundException("PROBLEM_NOT_FOUND", "Problem not found"));
    }

    private User requireActor(UUID actorPublicId) {
        return userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account no longer exists"));
    }

    /**
     * Turns a raw search term into a LIKE pattern, or null when there is nothing to match.
     *
     * <p>The wildcards and lower-casing are applied here rather than in the query so that
     * the bound parameter is a plain string. {@code %} and {@code _} in the user's term are
     * escaped: without that, a search for "100%" would match everything, and a term of
     * only wildcards would force a full scan.
     */
    /**
     * Resolves one optional text field against what is already stored.
     *
     * <ul>
     *   <li>{@code null} — the caller said nothing, so the existing value stands.</li>
     *   <li>blank — the caller is clearing the field, which is stored as NULL.</li>
     *   <li>anything else — the new value.</li>
     * </ul>
     *
     * <p>Blank becomes NULL rather than an empty string because the database forbids a
     * blank-but-present value ({@code ck_problems_statement_not_blank}). That constraint is
     * worth keeping: "a stored statement is never an empty string" is a real guarantee, and
     * NULL already means "not written yet". Storing {@code ""} would create a second way to
     * say absent, and the completeness check would then have to know about both.
     */
    private String resolveContent(String supplied, String existing) {
        if (supplied == null) {
            return existing;
        }
        return supplied.isBlank() ? null : supplied;
    }

    private String searchPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private String describe(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = e; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append(' ');
        }
        return text.toString();
    }

    /** Exposed for tests that assert the transition vocabulary stays in step with the enum. */
    public static List<String> auditedActions() {
        return List.of("PROBLEM_CREATED", "PROBLEM_UPDATED", "PROBLEM_PUBLISHED",
                "PROBLEM_UNPUBLISHED", "PROBLEM_ARCHIVED", "PROBLEM_RESTORED");
    }
}
