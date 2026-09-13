package com.codearena.contest.dto;

import com.codearena.contest.Contest;
import com.codearena.contest.ContestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A contest as it appears in a list.
 *
 * <p>No description, no problem list, no participant list: a catalogue page needs none of
 * them and a contest's problem set is not something to hand out before it starts.
 */
@Schema(description = "A contest in the catalogue.")
public record ContestSummaryResponse(
        UUID id,
        String title,
        String slug,
        @Schema(description = "Derived from the schedule and the current time, never stored.")
        ContestStatus status,
        Instant startAt,
        Instant endAt,
        long participantCount,
        int problemCount,
        @Schema(description = "Whether the caller is registered. False for anonymous callers.")
        boolean registered) {

    public static ContestSummaryResponse from(Contest contest, Instant now,
                                              long participants, int problems, boolean registered) {
        return new ContestSummaryResponse(
                contest.getPublicId(), contest.getTitle(), contest.getSlug(),
                contest.statusAt(now), contest.getStartAt(), contest.getEndAt(),
                participants, problems, registered);
    }
}
