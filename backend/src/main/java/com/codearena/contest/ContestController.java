package com.codearena.contest;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.common.PageRequests;
import com.codearena.common.PageResponse;
import com.codearena.contest.dto.ContestDetailResponse;
import com.codearena.contest.dto.ContestRegistrationResponse;
import com.codearena.contest.dto.ContestSummaryResponse;
import com.codearena.contest.dto.StandingsResponse;
import com.codearena.submission.SubmissionService;
import com.codearena.submission.dto.SubmissionAcceptedResponse;
import com.codearena.submission.dto.SubmissionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimited;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Contests, as contestants use them.
 *
 * <h2>What a client cannot send</h2>
 * There is no endpoint here that accepts a status, a score, a rank, a penalty, a
 * participant id or a timestamp. Registration takes no body at all, and a contest submission
 * takes the same two fields a practice submission does — a language and a source string. The
 * caller's identity comes from the session, never from a path or a payload.
 *
 * <p>Contest state is derived from the schedule and the server's clock on every request, so
 * a client cannot put a contest into a state that suits it by asking.
 */
@RestController
@Tag(name = "Contests", description = "Browsing, entering and competing in contests")
public class ContestController {

    private final ContestService contestService;
    private final ContestStandingsService standingsService;
    private final SubmissionService submissionService;

    public ContestController(ContestService contestService,
                             ContestStandingsService standingsService,
                             SubmissionService submissionService) {
        this.contestService = contestService;
        this.standingsService = standingsService;
        this.submissionService = submissionService;
    }

    /**
     * The contest catalogue.
     *
     * <p>Requires a session, like the problem catalogue: everything under {@code /api}
     * except registration and login does. Anonymous callers get 401 before reaching here.
     *
     * <p>Drafts are excluded by the query rather than filtered afterwards, so one cannot
     * reach a response through a forgotten branch.
     */
    @GetMapping("/api/contests")
    @Operation(summary = "List contests",
               description = """
                       Every contest a user may see, newest start first. Drafts are never
                       included, for anyone, on this endpoint — administrators use
                       `/api/admin/contests` to see those.

                       Requires a session, as the problem catalogue does.

                       `status` filters on the **derived** status — UPCOMING, LIVE, ENDED or
                       CANCELLED — which is computed from the schedule and the server's clock
                       rather than read from a column.

                       `registered` reflects the calling user, and is false for anonymous
                       callers.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of contests"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    public PageResponse<ContestSummaryResponse> list(
            @RequestParam(required = false) ContestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser viewer) {

        Pageable pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "startAt"));
        return contestService.list(status, pageable, viewer == null ? null : viewer.getPublicId());
    }

    /**
     * One contest.
     *
     * <p>Any authenticated user, but what comes back depends on the caller and on the clock.
     * In particular the <b>problem list is empty until the contest starts</b> — publishing a
     * contest says that it exists and when, not what is in it.
     */
    @GetMapping("/api/contests/{contestId}")
    @Operation(summary = "Contest detail",
               description = """
                       The contest page.

                       **The problem list is empty until the contest starts.** Releasing it
                       during UPCOMING would let registered users read every statement in
                       advance and begin solving before the clock did.

                       `serverTime` carries the server's instant so a countdown can be drawn
                       against a corrected clock rather than the browser's, which may have
                       drifted or been asleep. It is informational: the deadline is enforced
                       server-side on every submission.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The contest"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404",
                         description = "No such contest, or it is a draft", content = @Content)
    })
    public ContestDetailResponse detail(@PathVariable UUID contestId,
                                        @AuthenticationPrincipal AuthenticatedUser viewer) {
        return contestService.detail(contestId, viewer == null ? null : viewer.getPublicId());
    }

    /**
     * Registers the calling user.
     *
     * <p>No request body: the participant is whoever is authenticated. A body carrying a
     * user id is the obvious way to let one person register another, so there is not one.
     */
    @PostMapping("/api/contests/{contestId}/register")
    @Operation(summary = "Register for a contest",
               description = """
                       Registers the **authenticated caller**. There is no request body and
                       no way to name a different participant.

                       Registration closes when the contest starts: a contestant who joined
                       late would compete over a shorter window while the penalty clock still
                       ran from the contest's start, so their standing would not be
                       comparable with anyone else's.

                       Idempotent. Registering twice returns the original registration with
                       `alreadyRegistered: true` rather than failing, so a double-click or a
                       retried request cannot produce an error the user cannot act on.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registered, or already was"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "409", description = "Registration is closed", content = @Content)
    })
    public ContestRegistrationResponse register(@PathVariable UUID contestId,
                                                @AuthenticationPrincipal AuthenticatedUser caller) {
        return contestService.register(contestId, caller.getPublicId());
    }

    /**
     * Submits a solution inside a contest.
     *
     * <p>The same pipeline as a practice submission — same table, same queue, same worker,
     * same sandbox — with the contest's eligibility rules in front of it. There is no second
     * execution engine, because a second one would be a second place for a judging bug to
     * live.
     */
    @PostMapping("/api/contests/{contestId}/problems/{problemId}/submissions")
    @Operation(summary = "Submit to a contest problem",
               description = """
                       Queues a solution against a contest problem and returns immediately;
                       judging happens asynchronously in the existing sandbox.

                       Five things are checked server-side, none of them taken from the
                       request: the contest is visible, the problem genuinely belongs to that
                       contest, the caller is registered, the contest is LIVE **now**, and the
                       problem is still published.

                       The deadline is the half-open window `[startAt, endAt)` evaluated
                       against the server's clock. A browser still showing a running countdown
                       is refused all the same.

                       The request carries a language and a source string. There is no field
                       for a user, a verdict, a score, a status, a runtime or a timestamp.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Queued for judging"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404",
                         description = "No such contest, or the problem is not in it", content = @Content),
            @ApiResponse(responseCode = "409",
                         description = "Not registered, or the contest is not LIVE", content = @Content),
            @ApiResponse(responseCode = "429",
                         description = "Submitting faster than the judge can be asked to work", content = @Content)
    })
    // The same bucket as a practice submission, and deliberately so. The resource under
    // protection is one shared judging queue, so two allowances would let a contestant
    // apply twice the pressure to it -- and would make this endpoint a way around the
    // other. The allowance is sized for contest use rather than for idle browsing: a
    // competitor fixing a bug and resubmitting stays comfortably inside it, while a script
    // does not. Nothing here touches the deadline: the contest window is still evaluated
    // server-side inside the service, so a throttled submission is one that was never
    // accepted, never queued and never scored.
    @RateLimited(RateLimitPolicy.SUBMISSION)
    public ResponseEntity<SubmissionAcceptedResponse> submit(
            @PathVariable UUID contestId,
            @PathVariable UUID problemId,
            @Valid @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        SubmissionAcceptedResponse accepted =
                submissionService.submitToContest(contestId, problemId, request, caller.getPublicId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /**
     * The scoreboard.
     *
     * <p>Readable by any authenticated user for a contest that has started, including while
     * it is running — there is no scoreboard freeze. Carries a username, a rank, a score, a
     * penalty and a grid: no email, no internal identifier, no profile data, no source code.
     */
    @GetMapping("/api/contests/{contestId}/standings")
    @Operation(summary = "Contest standings",
               description = """
                       Computed from persisted submission results on every request; no score
                       is stored, so there is no cache to serve a stale ranking.

                       **Scoring.** A problem is solved by the first ACCEPTED submission to
                       it. Score is the sum of the points of solved problems. Penalty
                       accumulates only over solved problems: minutes from the contest start
                       to the solve, plus 20 per counted rejection made before it.

                       **Counted rejections** are WRONG_ANSWER, RUNTIME_ERROR,
                       TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED and COMPILATION_ERROR.
                       SYSTEM_ERROR is never counted: it means the judge failed, and a
                       contestant is not charged for our outage.

                       **Ordering.** Score descending, then penalty ascending, then the
                       earlier last solve, then user id for stability. Ranks are competition
                       ranks: genuine ties share a rank and the next rank skips.

                       Empty for a contest that has not started.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The standings"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404",
                         description = "No such contest, or it is a draft", content = @Content),
            @ApiResponse(responseCode = "429", description = "Reading standings too often", content = @Content)
    })
    // The most expensive read in the system: recomputed from the submission table on every
    // request, and more expensive the fuller the contest gets -- which is exactly when the
    // most people are watching. The allowance is several times what the page's own refresh
    // consumes, so a browser never reaches it.
    @RateLimited(RateLimitPolicy.STANDINGS)
    public StandingsResponse standings(@PathVariable UUID contestId) {
        return standingsService.standings(contestId);
    }
}
