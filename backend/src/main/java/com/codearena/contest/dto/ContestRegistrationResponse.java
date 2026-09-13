package com.codearena.contest.dto;

import com.codearena.contest.ContestStatus;

import java.time.Instant;
import java.util.UUID;

/** Confirms a registration. Idempotent: registering twice returns the original. */
public record ContestRegistrationResponse(
        UUID contestId,
        ContestStatus status,
        Instant registeredAt,
        boolean alreadyRegistered) {
}
