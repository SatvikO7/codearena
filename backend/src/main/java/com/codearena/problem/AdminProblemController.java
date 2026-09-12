package com.codearena.problem;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.common.PageResponse;
import com.codearena.problem.dto.AdminProblemDetailResponse;
import com.codearena.problem.dto.ProblemRequest;
import com.codearena.problem.dto.ProblemSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Problem authoring, for administrators.
 *
 * <p>Authorisation is enforced twice, deliberately. The filter chain from Phase 2 already
 * requires ADMIN for every {@code /api/admin/**} path; the class-level
 * {@link PreAuthorize} repeats it at the method boundary. The duplication is cheap and
 * means that moving these endpoints to a different path, or an edit to the URL rules,
 * cannot quietly expose problem authoring to ordinary users.
 *
 * <p>Problems are addressed here by UUID rather than slug, because a slug can be changed
 * and an administrative link must not break when it is.
 *
 * <p>Lifecycle changes are explicit endpoints, not a status field on the update payload.
 * {@link ProblemRequest} has no status property at all, so there is no request a client
 * can send that pushes a problem into the catalogue without passing the completeness
 * checks that publication runs.
 */
@RestController
@RequestMapping("/api/admin/problems")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Problem administration", description = "Authoring and lifecycle. Requires the ADMIN role.")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator", content = @Content)
})
public class AdminProblemController {

    private final ProblemAdminService adminService;

    public AdminProblemController(ProblemAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @Operation(summary = "List problems in any status",
               description = "Unlike the public catalogue, `status` is a filter here; omitting it returns all statuses.")
    public PageResponse<ProblemSummaryResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "NEWEST, OLDEST, TITLE or DIFFICULTY") @RequestParam(required = false) String sort,
            @Parameter(description = "Filter by lifecycle status; omit for all") @RequestParam(required = false) ProblemStatus status,
            @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) String search) {

        return adminService.list(status, difficulty, search, ProblemPageRequest.of(page, size, sort));
    }

    @GetMapping("/{problemId}")
    @Operation(summary = "Get a problem for authoring",
               description = "Includes judge test cases and the list of fields still blocking publication.")
    public AdminProblemDetailResponse get(@PathVariable UUID problemId) {
        return adminService.get(problemId);
    }

    @PostMapping
    @Operation(summary = "Create a problem",
               description = """
                       Creates the problem in DRAFT. Nothing is ever created already published.
                       The slug is derived from the title when not supplied.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "409", description = "Slug already in use", content = @Content)
    })
    public ResponseEntity<AdminProblemDetailResponse> create(
            @Valid @RequestBody ProblemRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {

        AdminProblemDetailResponse created = adminService.create(request, actor.getPublicId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{problemId}")
    @Operation(summary = "Update a problem",
               description = """
                       Updates the problem's content.

                       **Omitted means unchanged; an explicit empty value clears.** Send
                       `""` to erase a text field and `[]` to remove every example or test
                       case. Omitting `testCases` leaves them intact rather than deleting
                       an entire answer key because a field was absent.

                       The status cannot be changed here — use the lifecycle endpoints.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such problem", content = @Content),
            @ApiResponse(responseCode = "409", description = "Slug already in use", content = @Content)
    })
    public AdminProblemDetailResponse update(
            @PathVariable UUID problemId,
            @Valid @RequestBody ProblemRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {

        return adminService.update(problemId, request, actor.getPublicId());
    }

    @PostMapping("/{problemId}/publish")
    @Operation(summary = "Publish",
               description = """
                       Moves DRAFT to PUBLISHED, making the problem visible in the catalogue.

                       Refused with 400 if the problem is incomplete: a statement, input and
                       output formats, constraints, at least one example and at least one
                       test case are all required. The response names what is missing.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Published"),
            @ApiResponse(responseCode = "400", description = "Incomplete, or not a legal transition", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such problem", content = @Content)
    })
    public AdminProblemDetailResponse publish(@PathVariable UUID problemId,
                                              @AuthenticationPrincipal AuthenticatedUser actor) {
        return adminService.publish(problemId, actor.getPublicId());
    }

    @PostMapping("/{problemId}/unpublish")
    @Operation(summary = "Unpublish",
               description = "Returns a PUBLISHED problem to DRAFT, removing it from the catalogue.")
    public AdminProblemDetailResponse unpublish(@PathVariable UUID problemId,
                                                @AuthenticationPrincipal AuthenticatedUser actor) {
        return adminService.unpublish(problemId, actor.getPublicId());
    }

    @PostMapping("/{problemId}/archive")
    @Operation(summary = "Archive",
               description = "Withdraws the problem from the catalogue while retaining it, so existing references stay resolvable.")
    public AdminProblemDetailResponse archive(@PathVariable UUID problemId,
                                              @AuthenticationPrincipal AuthenticatedUser actor) {
        return adminService.archive(problemId, actor.getPublicId());
    }

    @PostMapping("/{problemId}/restore")
    @Operation(summary = "Restore an archived problem",
               description = """
                       Returns an ARCHIVED problem to DRAFT. It lands in DRAFT rather than
                       PUBLISHED so it cannot silently reappear in the catalogue; publishing
                       it again re-runs the completeness checks.
                       """)
    public AdminProblemDetailResponse restore(@PathVariable UUID problemId,
                                              @AuthenticationPrincipal AuthenticatedUser actor) {
        return adminService.restore(problemId, actor.getPublicId());
    }
}
