package com.codearena.audit;

/**
 * How an audited action ended.
 *
 * <h2>Where the line falls</h2>
 * Three outcomes, and the distinction between them is the policy for what gets audited at
 * all:
 *
 * <ul>
 *   <li>{@link #SUCCESS} — the action happened. Recorded in the same transaction as the
 *       change itself, so a success event cannot outlive a rolled-back mutation.</li>
 *   <li>{@link #FAILURE} — the action was attempted by somebody entitled to attempt it, and
 *       did not happen. Reserved for <b>security-relevant</b> failures: a rejected login, an
 *       attempt to modify a contest that has started. An ordinary validation error — a
 *       missing title, a malformed slug — is an API response and a log line, not an audit
 *       event. Auditing those would bury the events that matter.</li>
 *   <li>{@link #DENIED} — the caller was not entitled to attempt it. Authorisation refused
 *       the request. Always worth recording: one is a misclick, a pattern is probing.</li>
 * </ul>
 *
 * <p>The difference between FAILURE and DENIED is deliberately preserved rather than
 * collapsed into "did not work". "Alice tried to publish a contest and the schedule was
 * invalid" and "Alice tried to publish a contest and is not an administrator" are different
 * events, and only one of them is a security concern.
 */
public enum AuditOutcome {

    SUCCESS,
    FAILURE,
    DENIED
}
