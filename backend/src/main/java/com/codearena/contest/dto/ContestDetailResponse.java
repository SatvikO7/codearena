package com.codearena.contest.dto;

import com.codearena.contest.ContestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contest page.
 *
 * <p>{@code serverTime} is here on purpose. The frontend shows a countdown, and a countdown
 * computed against the browser's clock is wrong by however far that clock has drifted — on a
 * laptop resumed from sleep, that can be minutes. Sending the server's instant alongside the
 * schedule lets the client compute an offset once and count down against a corrected clock.
 *
 * <p>It remains informational. The deadline is enforced server-side on every submission; the
 * countdown reaching zero is a hint to disable a button, not the thing that closes the
 * contest.
 */
@Schema(description = "A contest, its schedule, and the caller's relationship to it.")
public record ContestDetailResponse(
        UUID id,
        String title,
        String slug,
        String description,
        ContestStatus status,
        Instant startAt,
        @Schema(description = "Exclusive: the contest is over at exactly this instant.")
        Instant endAt,
        @Schema(description = "The server's clock when this was served, for an honest countdown.")
        Instant serverTime,
        long participantCount,
        @Schema(description = "Whether the caller is registered.")
        boolean registered,
        @Schema(description = "Whether the caller may submit right now: registered, and the contest is LIVE.")
        boolean canSubmit,
        @Schema(description = "Whether this contest moves ratings. Fixed once it starts.")
        boolean rated,
        @Schema(description = """
                When rating finalisation ran, or null if it has not. Set for unrated contests                 too — it answers "has the question been settled", while `rated` answers "did                 settling it change anything".""")
        Instant ratingFinalizedAt,
        @Schema(description = "Empty until the contest starts, so a problem set is not disclosed early.")
        List<ContestProblemResponse> problems) {
}
