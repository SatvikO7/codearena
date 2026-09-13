package com.codearena.contest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A participant, for the admin participant list.
 *
 * <p>Username and registration time only. An administrator managing a contest needs to know
 * who is in it, not their email address — and this response is one careless change away from
 * being reused somewhere public.
 */
public record ContestParticipantResponse(UUID userId, String username, Instant registeredAt) {
}
