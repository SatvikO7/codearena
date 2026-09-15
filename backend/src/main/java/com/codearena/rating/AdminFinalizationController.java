package com.codearena.rating;

import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimited;
import com.codearena.rating.dto.RatingResponses.FinalizationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finalising a contest by hand. ADMIN only.
 *
 * <h2>Why this exists at all, when finalisation is automatic</h2>
 * The sweeper handles the normal case within a minute of a contest ending. This is for the
 * cases where waiting is not acceptable: a contest whose finalisation failed and whose cause
 * has now been fixed, a deployment where automatic finalisation is switched off, or simply an
 * administrator who wants the ratings out now rather than on the next tick.
 *
 * <p>It is the same operation, not a parallel one. There is deliberately no "force", no
 * "recalculate" and no "override" — every one of those would mean a second way for a rating to
 * change, and a rating with two possible provenances is a rating whose history no longer
 * explains it.
 *
 * <h2>Authorisation</h2>
 * Decided by the URL: {@code /api/admin/**} is ADMIN-only in the Phase 2 security
 * configuration. Nothing here re-checks it, because a check written here could be forgotten on
 * the next endpoint added to this class.
 *
 * <h2>It cannot rate a contest twice</h2>
 * Not because this class is careful, but because {@link ContestFinalizationService} claims the
 * contest with a conditional UPDATE and the history table has a unique constraint on
 * {@code (contest_id, user_id)}. An administrator may call this ten times in a row; the first
 * call finalises and the other nine report that it was already done.
 */
@RestController
@RequestMapping("/api/admin/contests")
@Tag(name = "Admin: ratings", description = "Finalising contests and producing rating changes")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Not an administrator", content = @Content)
})
public class AdminFinalizationController {

    private final ContestFinalizationService finalizationService;

    public AdminFinalizationController(ContestFinalizationService finalizationService) {
        this.finalizationService = finalizationService;
    }

    @PostMapping("/{contestId}/finalize")
    @Operation(summary = "Finalise a contest now",
               description = """
                       Computes rating changes from the contest's authoritative final
                       standings and writes them, once.

                       **Idempotent.** Calling it again returns `alreadyFinalized: true` and
                       changes nothing. That is a success, not an error — an administrator
                       whose connection dropped mid-request needs to be able to retry without
                       wondering whether they have just rated everybody twice.

                       **Refused** when the contest has not ended, is not published, or was
                       cancelled. A cancelled contest is never rated, whatever its standings
                       show: the scoreboard remains readable as a record of what happened, and
                       what is withheld is the consequence.

                       An unrated contest may still be finalised. It produces no rating changes
                       — finalisation on an unrated contest records that the question has been
                       settled, and settles it as "nothing".

                       Takes no request body. There is nothing to send: every input comes from
                       the database, and a rating that could be influenced by a payload would
                       not be a rating.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                         description = "Finalised, or already was"),
            @ApiResponse(responseCode = "400",
                         description = "Not ended, not published, or cancelled", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such contest", content = @Content),
            @ApiResponse(responseCode = "429",
                         description = "Too many administrative writes", content = @Content)
    })
    @RateLimited(RateLimitPolicy.ADMIN_WRITE)
    public FinalizationResult finalizeContest(@PathVariable UUID contestId) {
        ContestFinalizationService.Result result = finalizationService.finalize(
                contestId, ContestFinalizationService.Source.ADMIN);

        return new FinalizationResult(
                result.contestId(), result.alreadyFinalized(), result.rated(),
                result.ratedParticipants(), result.finalizedAt());
    }
}
