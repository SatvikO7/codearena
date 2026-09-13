package com.codearena.submission;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.common.PageResponse;
import com.codearena.common.PageRequests;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.dto.SubmissionAcceptedResponse;
import com.codearena.submission.dto.SubmissionDetailResponse;
import com.codearena.submission.dto.SubmissionRequest;
import com.codearena.submission.dto.SubmissionSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Submitting solutions and reading back verdicts.
 *
 * <p>Nothing here executes anything. {@code POST} writes a row, asks for it to be queued,
 * and returns — typically in a few milliseconds. The judging happens in a separate process
 * against a separate container, and the client learns the outcome by polling the detail
 * endpoint.
 */
@RestController
@Tag(name = "Submissions", description = "Submitting solutions and reading verdicts")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
})
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/api/problems/{problemId}/submissions")
    @Operation(summary = "Submit a solution",
               description = """
                       Queues a solution for judging and returns immediately with a
                       submission id and `QUEUED`. Poll `GET /api/submissions/{id}` until the
                       status is terminal.

                       The author is taken from the session and the problem from the path;
                       neither can be supplied in the body. Only published problems accept
                       submissions — a draft or archived problem answers 404, the same as one
                       that does not exist.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted into the queue"),
            @ApiResponse(responseCode = "400", description = "Unsupported language, empty source, or source too large", content = @Content),
            @ApiResponse(responseCode = "404", description = "No published problem with that id", content = @Content)
    })
    public ResponseEntity<SubmissionAcceptedResponse> submit(
            @PathVariable UUID problemId,
            @Valid @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal AuthenticatedUser author) {

        SubmissionAcceptedResponse accepted =
                submissionService.submit(problemId, request, author.getPublicId());

        // 202, not 201: the submission has been accepted for processing, and the thing the
        // client actually cares about - the verdict - does not exist yet.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    @GetMapping("/api/submissions/{submissionId}")
    @Operation(summary = "Get a submission",
               description = """
                       Returns the submission with its current status and, once judging has
                       finished, its verdict. Includes the source code, because the caller is
                       its author.

                       Somebody else's submission answers 404 rather than 403: telling an
                       unauthorised caller that a submission exists would make this endpoint
                       an oracle for valid ids.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The submission"),
            @ApiResponse(responseCode = "404", description = "No such submission, or it belongs to someone else", content = @Content)
    })
    public SubmissionDetailResponse get(@PathVariable UUID submissionId,
                                        @AuthenticationPrincipal AuthenticatedUser viewer) {
        return submissionService.get(submissionId, viewer.getPublicId(), viewer.getRole());
    }

    @GetMapping("/api/submissions")
    @Operation(summary = "List your submissions",
               description = """
                       Your own submission history, newest first. Source code is deliberately
                       omitted here; fetch a single submission to see it.

                       Paging follows the same rules as the problem catalogue: zero-based
                       `page`, `size` defaulting to 20 and clamped to 100.
                       """)
    // Declared explicitly: the class-level @ApiResponses suppresses springdoc's inferred
    // success response, which would otherwise leave this endpoint documenting only its 401.
    @ApiResponse(responseCode = "200", description = "A page of your submissions")
    public PageResponse<SubmissionSummaryResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Restrict to one problem") @RequestParam(required = false) UUID problemId,
            @Parameter(description = "Restrict to one verdict or lifecycle state") @RequestParam(required = false) SubmissionStatus status,
            @Parameter(description = "Restrict to one language") @RequestParam(required = false) Language language,
            @AuthenticationPrincipal AuthenticatedUser viewer) {

        return submissionService.listOwn(viewer.getPublicId(), problemId, status, language, newestFirst(page, size));
    }

    @GetMapping("/api/problems/{problemId}/submissions")
    @Operation(summary = "Your submissions for one problem",
               description = """
                       Your own attempts at a single problem, newest first — the same data as
                       `GET /api/submissions?problemId=…`, addressed from the problem.

                       This is **not** a public feed: it returns only the authenticated
                       caller's submissions, never anybody else's, whatever the problem.
                       """)
    @ApiResponse(responseCode = "200", description = "A page of your submissions for this problem")
    public PageResponse<SubmissionSummaryResponse> listForProblem(
            @PathVariable UUID problemId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Restrict to one verdict or lifecycle state") @RequestParam(required = false) SubmissionStatus status,
            @Parameter(description = "Restrict to one language") @RequestParam(required = false) Language language,
            @AuthenticationPrincipal AuthenticatedUser viewer) {

        return submissionService.listOwn(viewer.getPublicId(), problemId, status, language, newestFirst(page, size));
    }

    /**
     * Submissions are only ever listed newest first, so the closed sort vocabulary the
     * problem catalogue needs would be noise here. Ordering by the primary key rather than
     * by {@code createdAt} keeps it deterministic: two submissions made in the same
     * millisecond still have a defined order, so paging cannot show one twice and skip another.
     */
    private Pageable newestFirst(Integer page, Integer size) {
        return PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }
}
