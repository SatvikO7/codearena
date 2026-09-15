package com.codearena.rating;

import com.codearena.common.PageResponse;
import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimited;
import com.codearena.rating.dto.RatingResponses.ContestRatingResult;
import com.codearena.rating.dto.RatingResponses.HistoryEntry;
import com.codearena.rating.dto.RatingResponses.RankingEntry;
import com.codearena.rating.dto.RatingResponses.RatingProfile;
import com.codearena.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Ratings, rankings and competitive history.
 *
 * <h2>Everything here is a read, and every read is server-derived</h2>
 * There is no endpoint that accepts a rating, a rank or a contest result. A rating moves in
 * exactly one place — inside a contest finalisation, from standings the database computed —
 * and no request body anywhere in this system can influence it. That is not enforced by
 * validation; it is enforced by there being nothing to validate.
 *
 * <h2>Authentication</h2>
 * These require a session, like the rest of the API: {@code anyRequest().authenticated()} in
 * the security configuration covers them. A rating and a rank are public <em>among users</em>
 * — a leaderboard that hid who was on it would not be a leaderboard — but that is a different
 * thing from being public to the internet, and this project has never had an anonymous read
 * surface beyond the catalogue.
 */
@RestController
@Tag(name = "Ratings", description = "Competitive ratings, rankings and contest history")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
})
public class RatingController {

    private final RatingQueryService queryService;

    public RatingController(RatingQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/rankings")
    @Operation(summary = "The global ranking",
               description = """
                       Competitors by rating, highest first, one page at a time.

                       Only accounts that have completed a rated contest appear. An account
                       that has never competed has no rating — which is different from having
                       a rating of 1500 — and listing it would be inventing a result.

                       **Rank is a competition rank**: two competitors on the same rating share
                       a rank and the next rank skips (1, 2, 2, 4). Rows are additionally
                       ordered by contests rated and then by identity, so a page boundary is
                       stable between requests — but that ordering does not change anybody's
                       rank. A result and a row number are different things.

                       Ordered, ranked and paged by the database. `size` is clamped to 100.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of the ranking"),
            @ApiResponse(responseCode = "429", description = "Reading the ranking too often",
                         content = @Content)
    })
    // The ranking is the most expensive read this phase adds: a window function over every
    // rated user. Sized like the other authenticated reads rather than given a policy of its
    // own -- a browser paging a leaderboard is nowhere near it.
    @RateLimited(RateLimitPolicy.STANDINGS)
    public PageResponse<RankingEntry> ranking(
            @Parameter(description = "Zero-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Items per page; clamped to 100") @RequestParam(required = false) Integer size) {
        return queryService.ranking(page == null ? 0 : page, size == null ? 50 : size);
    }

    @GetMapping("/api/users/{userId}/rating")
    @Operation(summary = "A competitor's rating profile",
               description = """
                       Current rating, peak, global rank, how many rated contests they have
                       completed, their recent results and their rating progression.

                       An account that has never been rated returns a profile with nulls rather
                       than a 404: "this competitor has not competed" is a real answer, and the
                       nulls say it without pretending the rating is zero.

                       Carries no email, no role and no account state — only what a leaderboard
                       already shows plus the history behind it.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The profile"),
            @ApiResponse(responseCode = "404", description = "No such user", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content)
    })
    @RateLimited(RateLimitPolicy.STANDINGS)
    public RatingProfile profile(@PathVariable UUID userId) {
        return queryService.profile(userId);
    }

    @GetMapping("/api/users/{userId}/rating/history")
    @Operation(summary = "A competitor's rated contest history",
               description = """
                       Every rated contest they have completed, newest first, with the rank,
                       score, penalty and rating movement of each.

                       Unrated contests never appear. There is no rating data for them, and
                       inventing a row with zeroes would be reporting a result that does not
                       exist.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of history"),
            @ApiResponse(responseCode = "404", description = "No such user", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content)
    })
    @RateLimited(RateLimitPolicy.STANDINGS)
    public PageResponse<HistoryEntry> history(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queryService.history(userId, page == null ? 0 : page, size == null ? 20 : size);
    }

    @GetMapping("/api/contests/{contestId}/rating")
    @Operation(summary = "Your rating outcome for one contest",
               description = """
                       What this contest did to **your** rating. The caller is taken from the
                       session; there is no parameter for whose result to return.

                       Four distinct states, and the distinction is the point:

                       - `UNRATED` — the contest does not move ratings, and never will.
                       - `CANCELLED` — it was called off. There will never be a rating.
                       - `PENDING` — it is rated and has ended, and finalisation has not run
                         yet. A rating is coming.
                       - `FINALIZED` — here is the result. If you did not compete, the rating
                         fields are null.

                       A pending contest deliberately does **not** report a change of zero.
                       Zero is a real rating change; "not yet" is not, and a page that showed
                       the two the same way would be lying about one of them.

                       No predicted or provisional rating change is offered while a contest is
                       running. A number that is labelled unofficial is still a number people
                       will quote, and it would be wrong as often as the standings moved.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The rating outcome"),
            @ApiResponse(responseCode = "404", description = "No such contest", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content)
    })
    @RateLimited(RateLimitPolicy.STANDINGS)
    public ContestRatingResult contestResult(@PathVariable UUID contestId,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        return queryService.contestResult(contestId, caller.getPublicId());
    }
}
