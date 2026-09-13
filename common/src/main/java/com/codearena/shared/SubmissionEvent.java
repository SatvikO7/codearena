package com.codearena.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification that a submission's state changed.
 *
 * <p>Deliberately tiny: an identifier, the new status, and when the row was written. It is
 * a <em>hint that something happened</em>, not the thing itself. The API server re-reads
 * the submission from PostgreSQL before telling any client anything, so a malformed, stale
 * or replayed event can delay a browser update but can never make it show something the
 * database does not say.
 *
 * <p>That is the whole reason the payload carries no verdict detail, no metrics and no
 * message. Putting them here would make Redis a second copy of the truth, and the two
 * copies would eventually disagree.
 *
 * <p>{@code occurredAt} is the row's {@code updated_at}, and it is what lets a client
 * discard an event older than what it already has — the convergence rule that makes
 * out-of-order and duplicate delivery harmless. See docs/submission-lifecycle.md.
 *
 * <p>Lives in the shared module because the worker publishes these and the API server
 * consumes them. It is a plain record with no annotations, so {@code common} stays free of
 * a serialisation dependency; each side uses its own Jackson mapper, which handles records
 * without help.
 */
public record SubmissionEvent(
        UUID submissionId,
        SubmissionStatus status,
        Instant occurredAt) {

    /**
     * The Redis Pub/Sub channel these travel on.
     *
     * <p>Pub/Sub, and therefore <strong>not durable</strong>: a subscriber that is not
     * connected at the moment of publication never receives the message, and there is no
     * replay. That is an acceptable trade only because the database is authoritative and
     * every consumer re-reads it. Anything that needed guaranteed delivery would need a
     * stream or an outbox instead, and would be a different design.
     */
    public static final String CHANNEL = "codearena:submissions:events";
}
