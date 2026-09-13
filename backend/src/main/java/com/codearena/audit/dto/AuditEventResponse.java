package com.codearena.audit.dto;

import com.codearena.audit.ActorType;
import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEvent;
import com.codearena.audit.AuditOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit event, as an administrator sees it.
 *
 * <p>A closed record. It carries the public user id and the username — never the internal
 * id, never an email address, never anything about the account beyond who acted. The
 * metadata is whatever the recording code curated, which by construction never includes a
 * password, a session identifier, a token, source code or hidden test data.
 *
 * <p>There is no field here for anything a caller could write back: the audit API is
 * read-only, and this type exists only to be serialised out.
 */
@Schema(description = "A recorded security-sensitive or administrative event.")
public record AuditEventResponse(

        UUID id,

        @Schema(description = "Written by the database when the event was recorded, "
                            + "never supplied by the application or a client.")
        Instant occurredAt,

        @Schema(description = "The acting user's public id, or null for an anonymous or system actor.")
        UUID actorUserId,

        @Schema(description = "Copied in when the event was written, so it survives a rename or deletion.")
        String actorUsername,

        ActorType actorType,
        AuditAction action,
        AuditOutcome outcome,

        @Schema(description = "What was acted on, e.g. PROBLEM or CONTEST.", example = "CONTEST")
        String entityType,

        @Schema(description = "The target's public identifier, as text.")
        String entityId,

        @Schema(description = "Correlates this event with the application log lines from the same request.")
        String requestId,

        @Schema(description = "Curated, bounded detail. Never a request body; never a secret.")
        Map<String, Object> metadata) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getPublicId(), event.getOccurredAt(),
                event.getActorUserId(), event.getActorUsername(), event.getActorType(),
                event.getAction(), event.getOutcome(),
                event.getEntityType(), event.getEntityId(),
                event.getRequestId(), event.getMetadata());
    }
}
