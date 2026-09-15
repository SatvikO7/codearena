package com.codearena.audit;

/**
 * The vocabulary of auditable events.
 *
 * <h2>What belongs here</h2>
 * Security-sensitive and administrative events: things somebody investigating an incident,
 * or answering "who changed this and when", would need. <b>Not</b> every method call, and
 * not ordinary reads.
 *
 * <p>The test is whether the event would be worth reading a year later. A contest being
 * cancelled qualifies. {@code GET /api/contests} does not — auditing it would bury the
 * events that matter under millions that do not, which is how an audit log becomes
 * unusable rather than merely large.
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li><b>Reads.</b> No browsing, listing or fetching is audited. They are the bulk of
 *       traffic and none of them change anything.</li>
 *   <li><b>Ordinary validation failures.</b> A malformed problem body is an API error, not
 *       a security event; see {@link AuditOutcome} for where the line falls.</li>
 *   <li><b>Judging outcomes.</b> A verdict is domain state, already durable in the
 *       submissions table. Copying it here would duplicate the truth rather than record
 *       who did something.</li>
 * </ul>
 *
 * <h2>Extending it</h2>
 * The constant is what is stored — as text, so adding one needs no migration. Renaming one
 * would orphan the history written under the old name, so constants are added and, if they
 * fall out of use, left in place.
 */
public enum AuditAction {

    // ------------------------------------------------------------------ authentication

    /** An account was created. Actor is the new account itself. */
    AUTH_REGISTER,

    /** A session was established. */
    AUTH_LOGIN,

    /**
     * A login attempt was rejected.
     *
     * <p>The most security-relevant event in the system: a run of these against one account,
     * or from one source, is what a credential-stuffing attempt looks like. Metadata carries
     * the reason and the identifier that was tried — never the password, and never anything
     * that would say whether the account exists.
     */
    AUTH_LOGIN_FAILURE,

    /** A session was ended deliberately. Expiry is not audited; nobody did it. */
    AUTH_LOGOUT,

    // ------------------------------------------------------------------ problems

    PROBLEM_CREATE,
    PROBLEM_UPDATE,
    PROBLEM_PUBLISH,
    PROBLEM_UNPUBLISH,
    PROBLEM_ARCHIVE,
    PROBLEM_RESTORE,

    // ------------------------------------------------------------------ contests

    CONTEST_CREATE,
    CONTEST_UPDATE,
    CONTEST_PUBLISH,

    /** Recorded at WARN in the log as well: stopping a contest people are competing in. */
    CONTEST_CANCEL,

    /** Only ever possible for an untouched draft; anything else is refused. */
    CONTEST_DELETE,

    CONTEST_PROBLEM_ADD,
    CONTEST_PROBLEM_UPDATE,
    CONTEST_PROBLEM_REMOVE,

    /** A user entered a contest. Not an administrative act, but it decides eligibility. */
    CONTEST_REGISTER,

    /**
     * A contest's results were turned into rating changes.
     *
     * <p>Recorded once per contest, in the finalisation transaction -- so a rolled-back
     * finalisation leaves no event claiming it happened, and a finalisation cannot commit
     * unaudited.
     *
     * <p>The metadata is deliberately three numbers. The detailed record of who moved from
     * what to what is contest_rating_changes, which is itself append-only; copying it into
     * an audit payload would duplicate a permanent record into another permanent record.
     */
    CONTEST_FINALIZE,

    /**
     * A finalisation was attempted and failed.
     *
     * <p>Its own action because the failure is otherwise invisible: the contest simply stays
     * unrated and the sweeper tries again later. Recorded independently of the transaction
     * that rolled back, which is the only way to record something about a rollback.
     */
    CONTEST_FINALIZE_FAILED,

    // ------------------------------------------------------------------ submissions

    /**
     * A submission was accepted for judging.
     *
     * <p>The event records that somebody submitted, never <em>what</em> they submitted. No
     * source code reaches an audit record.
     */
    SUBMISSION_CREATE,

    // ------------------------------------------------------------------ access control

    /**
     * An authenticated caller was refused an administrative endpoint.
     *
     * <p>Always {@link AuditOutcome#DENIED}. One of these is a misclick; a pattern of them
     * is somebody probing the admin surface, and that is exactly what an audit log is for.
     */
    ADMIN_ACCESS_DENIED,

    /**
     * A rate limit was reached, and the caller kept going.
     *
     * <p>Always {@link AuditOutcome#DENIED}, and deliberately <b>not</b> written once per
     * rejected request. A single event stands for a burst of rejections by one identity
     * under one policy, over a configured cooldown. Recording each rejection would let
     * anybody who can be rate limited write unbounded rows into a table that cannot be
     * deleted -- a rejected request would become a way to attack the audit log itself.
     *
     * <p>Only policies keyed on something bounded produce these. See
     * {@link com.codearena.ratelimit.RateLimitViolationAuditor}.
     */
    RATE_LIMIT_EXCEEDED
}
