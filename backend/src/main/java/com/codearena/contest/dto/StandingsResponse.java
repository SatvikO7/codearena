package com.codearena.contest.dto;

import com.codearena.contest.ContestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The scoreboard.
 *
 * <p>Carries only what a scoreboard needs: a username, a rank, a score, a penalty and a grid
 * of per-problem outcomes. <b>No email, no ids beyond the public one, no profile data, and
 * no source code.</b> A public leaderboard is the widest-read page a contest has, and
 * anything on it is effectively published.
 */
@Schema(description = "Contest standings, computed from persisted submission results.")
public record StandingsResponse(
        UUID contestId,
        ContestStatus status,
        Instant computedAt,
        @Schema(description = "Problem columns, in contest order.")
        List<Column> problems,
        List<Row> rows) {

    @Schema(description = "One problem column of the standings grid.")
    public record Column(UUID problemId, String label, String title, int points) {
    }

    @Schema(description = "One contestant's row.")
    public record Row(
            @Schema(description = "Competition rank: ties share a rank and the next rank skips.")
            int rank,
            UUID userId,
            String username,
            int solved,
            int score,
            @Schema(description = "Total penalty minutes over solved problems.")
            int penalty,
            List<Cell> cells) {
    }

    @Schema(description = "One contestant's result on one problem.")
    public record Cell(
            UUID problemId,
            boolean solved,
            @Schema(description = "Counted attempts. SYSTEM_ERROR is never counted.")
            int attempts,
            @Schema(description = "Penalty contributed by this problem, or null if unsolved.")
            Integer penaltyMinutes,
            @Schema(description = "Minutes from contest start to the solve, or null if unsolved.")
            Integer solvedAtMinute) {
    }
}
