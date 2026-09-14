package com.codearena.problem;

import com.codearena.common.PageResponse;
import com.codearena.problem.dto.ProblemDetailResponse;
import com.codearena.problem.dto.ProblemSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimited;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public problem catalogue.
 *
 * <p>Reachable by any authenticated user; `anyRequest().authenticated()` from Phase 2
 * covers these paths, so an anonymous caller receives 401. Only PUBLISHED problems are
 * ever returned, and the status is chosen by the service rather than accepted from the
 * request.
 *
 * <p>Problems are addressed here by slug, which is the handle that appears in a URL and
 * that users share. The UUID remains the canonical identifier for mutation and for
 * cross-references from other entities; it is present in every response as {@code id}.
 */
@RestController
@RequestMapping("/api/problems")
@Tag(name = "Problems", description = "Browsing published problems")
public class ProblemController {

    private final ProblemCatalogService catalogService;

    public ProblemController(ProblemCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(summary = "List published problems",
               description = """
                       Returns a page of published problems. Drafts and archived problems are
                       never included, whatever parameters are supplied.

                       Paging is zero-based. `size` defaults to 20 and is clamped to 100.
                       Ordering always ends with a unique tiebreaker, so paging is stable.
                       """)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "A page of published problems"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "Searching too often. Applies only when `search` is supplied; "
                                + "listing without a search term is not rate limited.",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    // Limited only when a search term is supplied: a plain page of the catalogue is an
    // indexed read, while a term runs a trigram match across every problem. Charging the
    // cheap case the same allowance as the expensive one would throttle ordinary browsing
    // to protect against something ordinary browsing does not do.
    @RateLimited(value = RateLimitPolicy.PROBLEM_SEARCH, onlyWhenParameterPresent = "search")
    public PageResponse<ProblemSummaryResponse> list(
            @Parameter(description = "Zero-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Items per page; clamped to 100") @RequestParam(required = false) Integer size,
            @Parameter(description = "NEWEST, OLDEST, TITLE or DIFFICULTY") @RequestParam(required = false) String sort,
            @Parameter(description = "Filter by difficulty") @RequestParam(required = false) Difficulty difficulty,
            @Parameter(description = "Filter by topic tag") @RequestParam(required = false) ProblemTag tag,
            @Parameter(description = "Case-insensitive substring of title or slug") @RequestParam(required = false) String search) {

        return catalogService.listPublished(
                difficulty, tag, search, ProblemPageRequest.of(page, size, sort));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a published problem",
               description = """
                       Returns the full statement, formats, constraints and worked examples.

                       Judge test cases are never included. A draft or archived problem
                       returns 404 rather than 403, so the response cannot be used to
                       discover that an unreleased problem exists.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The problem"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No published problem with that slug", content = @Content)
    })
    public ProblemDetailResponse get(@PathVariable String slug) {
        return catalogService.getPublishedBySlug(slug);
    }
}
