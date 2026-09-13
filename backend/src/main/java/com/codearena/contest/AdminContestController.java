package com.codearena.contest;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.common.PageRequests;
import com.codearena.common.PageResponse;
import com.codearena.contest.dto.ContestDetailResponse;
import com.codearena.contest.dto.ContestParticipantResponse;
import com.codearena.contest.dto.ContestProblemRequest;
import com.codearena.contest.dto.ContestProblemUpdateRequest;
import com.codearena.contest.dto.ContestRequest;
import com.codearena.contest.dto.ContestSummaryResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Contest authoring. ADMIN only.
 *
 * <p>Authorisation is not decided here: {@code /api/admin/**} is restricted to ADMIN by the
 * Phase 2 security configuration, so every method below is unreachable without the role.
 * Putting the check in the URL rather than in each handler means a new endpoint added to
 * this class is protected by default rather than by remembering an annotation.
 *
 * <h2>Editing stops when the contest starts</h2>
 * Every mutating endpoint here refuses once the contest is LIVE or ENDED. That is enforced
 * on the {@link Contest} aggregate, not in this controller, so the rule cannot be bypassed by
 * a future caller that does not know about it.
 */
@RestController
@RequestMapping("/api/admin/contests")
@Tag(name = "Admin: contests", description = "Creating and managing contests")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Not an administrator", content = @Content)
})
public class AdminContestController {

    private final ContestAdminService adminService;

    public AdminContestController(ContestAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @Operation(summary = "List every contest, drafts included")
    @ApiResponse(responseCode = "200", description = "A page of contests")
    public PageResponse<ContestSummaryResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "startAt"));
        return adminService.list(pageable);
    }

    @GetMapping("/{contestId}")
    @Operation(summary = "Contest detail",
               description = "Includes the problem set at every stage, unlike the public view.")
    @ApiResponse(responseCode = "200", description = "The contest")
    public ContestDetailResponse detail(@PathVariable UUID contestId) {
        return adminService.detail(contestId);
    }

    @PostMapping
    @Operation(summary = "Create a contest",
               description = """
                       Creates a DRAFT. Drafts are invisible to normal users and freely
                       editable.

                       Timestamps are ISO-8601 with an offset and are stored in UTC. The
                       schedule is validated on the way in: the end must follow the start,
                       and the contest must run for between 5 minutes and 14 days — the upper
                       bound catching a mistyped year rather than enforcing a competition rule.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created as a draft"),
            @ApiResponse(responseCode = "409", description = "Slug already taken", content = @Content)
    })
    public ResponseEntity<ContestDetailResponse> create(@Valid @RequestBody ContestRequest request,
                                                        @AuthenticationPrincipal AuthenticatedUser admin) {
        ContestDetailResponse created = adminService.create(request, admin.getPublicId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{contestId}")
    @Operation(summary = "Edit a contest",
               description = """
                       Allowed while the contest is DRAFT or UPCOMING. **Refused once it is
                       LIVE or ENDED**: moving the end time would invalidate every penalty
                       already computed, and there is deliberately no override.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated contest"),
            @ApiResponse(responseCode = "400",
                         description = "The contest has started, or the schedule is invalid",
                         content = @Content)
    })
    public ContestDetailResponse update(@PathVariable UUID contestId,
                                        @Valid @RequestBody ContestRequest request) {
        return adminService.update(contestId, request);
    }

    @PostMapping("/{contestId}/publish")
    @Operation(summary = "Publish a contest",
               description = """
                       Makes a draft visible and opens registration. Requires at least one
                       problem — publishing an empty contest lets people register for
                       something they cannot compete in.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The published contest"),
            @ApiResponse(responseCode = "400",
                         description = "No problems, or the contest has already ended",
                         content = @Content)
    })
    public ContestDetailResponse publish(@PathVariable UUID contestId) {
        return adminService.publish(contestId);
    }

    @PostMapping("/{contestId}/cancel")
    @Operation(summary = "Cancel a contest",
               description = """
                       Calls a contest off. Permitted from DRAFT and from PUBLISHED
                       **including while it is running** — a broken problem or a leaked test
                       set is a real reason to stop a contest, and refusing would leave no
                       honest option but to let a spoiled contest finish.

                       Submissions stop immediately. Those already made are kept, and the
                       standings remain readable as a historical record. A cancelled contest
                       is never resurrected.

                       An ENDED contest cannot be cancelled: its result is already history.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The cancelled contest"),
            @ApiResponse(responseCode = "400",
                         description = "Already ended, or already cancelled", content = @Content)
    })
    public ContestDetailResponse cancel(@PathVariable UUID contestId) {
        return adminService.cancel(contestId);
    }

    @DeleteMapping("/{contestId}")
    @Operation(summary = "Delete a contest",
               description = """
                       Permitted **only for an untouched draft**: never published, nobody
                       registered, nothing submitted. Anything else is a record of what people
                       did; cancel it instead.

                       The database enforces this independently — the submissions foreign key
                       is ON DELETE RESTRICT — so a mistake here still cannot destroy
                       submission history.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "409",
                         description = "Published, or has participants or submissions", content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable UUID contestId) {
        adminService.delete(contestId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ problems

    @PostMapping("/{contestId}/problems")
    @Operation(summary = "Add a problem to a contest",
               description = """
                       The problem must already be PUBLISHED, and may appear in a contest only
                       once. It is appended after the existing problems; use the update
                       endpoint to move it.

                       Returns the whole contest, not just the problem list, so a caller
                       gets the re-labelled problems and the current status in one response.

                       Refused once the contest has started.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The contest, with its problems"),
            @ApiResponse(responseCode = "400",
                         description = "The problem is not published, or the contest has started",
                         content = @Content),
            @ApiResponse(responseCode = "404", description = "No such contest or problem",
                         content = @Content),
            @ApiResponse(responseCode = "409", description = "Already in this contest",
                         content = @Content)
    })
    public ContestDetailResponse addProblem(@PathVariable UUID contestId,
                                            @Valid @RequestBody ContestProblemRequest request) {
        return adminService.addProblem(contestId, request);
    }

    @PutMapping("/{contestId}/problems/{problemId}")
    @Operation(summary = "Change a problem's points or position",
               description = """
                       Both fields are optional; omitted means unchanged, so changing the
                       points cannot accidentally clobber a concurrent reorder.

                       Refused once the contest has started — changing what a problem is worth
                       mid-contest silently rewrites the standings of everyone who already
                       solved it.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The contest, with its problems"),
            @ApiResponse(responseCode = "400", description = "The contest has started",
                         content = @Content),
            @ApiResponse(responseCode = "404", description = "Not part of this contest",
                         content = @Content)
    })
    public ContestDetailResponse updateProblem(@PathVariable UUID contestId,
                                               @PathVariable UUID problemId,
                                               @Valid @RequestBody ContestProblemUpdateRequest request) {
        return adminService.updateProblem(contestId, problemId, request);
    }

    @DeleteMapping("/{contestId}/problems/{problemId}")
    @Operation(summary = "Remove a problem from a contest",
               description = "Remaining problems are renumbered so labels stay A, B, C. "
                           + "Refused once the contest has started.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The contest, with its problems"),
            @ApiResponse(responseCode = "400", description = "The contest has started",
                         content = @Content),
            @ApiResponse(responseCode = "404", description = "Not part of this contest",
                         content = @Content)
    })
    public ContestDetailResponse removeProblem(@PathVariable UUID contestId,
                                               @PathVariable UUID problemId) {
        return adminService.removeProblem(contestId, problemId);
    }

    @GetMapping("/{contestId}/participants")
    @Operation(summary = "List participants",
               description = "Username and registration time only. An administrator managing a "
                           + "contest needs to know who is in it, not their email address.")
    @ApiResponse(responseCode = "200", description = "The participants, oldest registration first")
    public List<ContestParticipantResponse> participants(@PathVariable UUID contestId) {
        return adminService.participants(contestId);
    }
}
