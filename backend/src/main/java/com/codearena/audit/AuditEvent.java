package com.codearena.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One recorded event. Append-only, and immutable once written.
 *
 * <h2>How immutability is enforced</h2>
 * Three layers, because this is the table whose integrity matters most exactly when somebody
 * with access is the problem:
 *
 * <ol>
 *   <li>{@link Immutable} — Hibernate will not issue an UPDATE for this entity, and there
 *       are no setters to make one meaningful.</li>
 *   <li>No repository method and no endpoint modifies or removes an event. The audit API is
 *       read-only.</li>
 *   <li>A database trigger raises on UPDATE and DELETE. "The code does not do that" is a
 *       weaker guarantee than "the database refuses", and an audit log an administrator can
 *       quietly edit is not an audit log.</li>
 * </ol>
 *
 * <h2>Why the actor is denormalised, and not a foreign key</h2>
 * {@code actorUsername} is copied in at write time rather than joined from {@code users}, and
 * {@code actorUserId} carries no foreign key. An audit record has to stay readable after an
 * account is renamed or deleted: a join that returns nothing is not an answer to "who did
 * this".
 *
 * <p>A foreign key would also collide with the append-only rule. It has to do <em>something</em>
 * when a user is deleted, and every option is wrong — CASCADE erases the history of what they
 * did, and SET NULL is an UPDATE, which the trigger refuses. The same reasoning already
 * applies to {@code entityId}, and applies here for the same reason.
 *
 * <h2>Why the timestamp is the database's</h2>
 * {@code occurred_at} defaults to {@code now()} in PostgreSQL and is never set by the
 * application. A timestamp the caller cannot influence is most of the point of an audit
 * timestamp, and one clock across all writers avoids a log whose ordering depends on which
 * application instance handled the request.
 */
@Entity
@Table(name = "audit_events")
@Immutable
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, insertable = false)
    private UUID publicId;

    /** Written by PostgreSQL's {@code DEFAULT now()}; read back after insert. */
    @Column(name = "occurred_at", nullable = false, updatable = false, insertable = false)
    private Instant occurredAt;

    /** The user's public id. The project never exposes {@code users.id}, so nor does this. */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_username", length = 150, updatable = false)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private ActorType actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private AuditOutcome outcome;

    @Column(name = "entity_type", length = 32, updatable = false)
    private String entityType;

    /**
     * The target's public identifier as text — a UUID or a slug.
     *
     * <p>Not a foreign key, deliberately: the record must survive the entity's deletion, and
     * a foreign key would prevent exactly the deletion whose record matters most.
     */
    @Column(name = "entity_id", length = 200, updatable = false)
    private String entityId;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    /**
     * Mapped with Hibernate's own JSON support rather than a third-party type library.
     *
     * <p>{@code SqlTypes.JSON} against a {@code jsonb} column uses the Jackson mapper that is
     * already on the classpath, so storing structured metadata costs no new dependency — and
     * a dependency added for one column in one table is a dependency to keep patched for
     * ever.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    protected AuditEvent() {
        // Required by JPA.
    }

    /**
     * Builds an event.
     *
     * <p>Package-private: events are created through {@link AuditService}, so that every
     * write goes through one place with one set of rules rather than being assembled
     * wherever somebody happens to need one.
     */
    AuditEvent(UUID actorUserId, String actorUsername, ActorType actorType,
               AuditAction action, AuditOutcome outcome,
               String entityType, String entityId,
               String requestId, Map<String, Object> metadata) {
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.actorType = actorType;
        this.action = action;
        this.outcome = outcome;
        this.entityType = entityType;
        this.entityId = entityId;
        this.requestId = requestId;
        this.metadata = metadata == null || metadata.isEmpty() ? null : metadata;
    }

    // ------------------------------------------------------------------ accessors
    // Read-only throughout. There are deliberately no setters.

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : metadata;
    }

    /** Identifies the event without printing its metadata, which is curated but not public. */
    @Override
    public String toString() {
        return "AuditEvent{action=%s, outcome=%s, actor=%s, entity=%s/%s}"
                .formatted(action, outcome, actorUsername, entityType, entityId);
    }
}
