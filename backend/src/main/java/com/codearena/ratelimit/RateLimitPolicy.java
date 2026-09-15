package com.codearena.ratelimit;

/**
 * The complete set of rate-limit policies, and what each one is protecting.
 *
 * <p>A closed enum rather than free-form strings. The identifier becomes part of a Redis
 * key and a metric tag, so it must be bounded and stable; a string parameter would put
 * both at the mercy of a typo, and a typo would silently create a brand-new empty bucket
 * that never rejects anything.
 *
 * <h2>What is deliberately not here</h2>
 * There is no policy for ordinary browsing — the problem catalogue, contest listings,
 * submission history. Those are indexed, paginated queries whose cost is what a database
 * is for, and limiting them buys a little protection against a nuisance at the price of
 * breaking a legitimate user who opens six tabs. Only reads that do materially more work
 * than a page fetch are limited, and each one below says why.
 */
public enum RateLimitPolicy {

    /**
     * Every login attempt from one caller, whatever account it names.
     *
     * <p>The flood cap. Generous, because the client identity behind this deployment can
     * be shared by many honest users (see {@link CallerIdentity}), and a strict limit on a
     * shared identity is a denial of service against everybody who shares it. The tight
     * control on guessing is {@link #LOGIN_ACCOUNT}, which does not share.
     */
    LOGIN_ORIGIN("login-origin", Scope.CLIENT, FailureMode.CLOSED, true),

    /**
     * Failed login attempts against one account, whoever is making them.
     *
     * <p>This is the real brute-force control: it holds however the attempts are spread
     * across sources, which is the shape credential stuffing actually takes. It counts
     * only failures — a successful login empties the record — so a legitimate user with
     * the right password is never accumulating against themselves.
     *
     * <p>Not auditable, and that is a security property rather than an oversight: the
     * identity here is derived from a caller-supplied username, so auditing rejections
     * would let anybody mint unbounded rows in an append-only table simply by inventing
     * usernames. See {@link RateLimitViolationAuditor}.
     */
    LOGIN_ACCOUNT("login-account", Scope.ACCOUNT, FailureMode.CLOSED, false),

    /**
     * Account creation, per caller.
     *
     * <p>Every registration writes a permanent row to the authoritative database and
     * consumes a username. This does not replace the unique constraints or the password
     * policy; it limits how fast an automated client can work through them.
     */
    REGISTRATION("registration", Scope.CLIENT, FailureMode.CLOSED, true),

    /**
     * Submissions per user, across practice and contests alike.
     *
     * <p>The single most important control in this phase. A submission is cheap to accept
     * and expensive to judge: it compiles and runs untrusted code in a container against
     * every test case. One scripted user can therefore fill the queue far faster than the
     * worker pool drains it, delaying everybody else's verdicts — an availability failure
     * that needs no exploit at all.
     *
     * <p>Practice and contest submissions share this bucket, deliberately. The resource
     * being protected is one shared judging queue, so splitting the allowance in two would
     * let a user apply twice the pressure to it and would hand anyone a bypass: rejected
     * on one endpoint, submit the same work through the other.
     */
    SUBMISSION("submission", Scope.USER, FailureMode.CLOSED, true),

    /**
     * Contest standings, per user.
     *
     * <p>Standings are recomputed from the submission table on each request rather than
     * stored, so this is the most expensive read in the system and gets more expensive as
     * a contest fills up — exactly when the most people are asking for it.
     */
    STANDINGS("standings", Scope.USER, FailureMode.OPEN, true),

    /**
     * Text search of the problem catalogue, per user.
     *
     * <p>Only searching, not listing. A plain page of problems is an indexed read; a search
     * term runs a trigram match across the catalogue, which is a different order of work.
     */
    PROBLEM_SEARCH("problem-search", Scope.USER, FailureMode.OPEN, true),

    /**
     * Administrative reads, per administrator.
     *
     * <p>Administrators are limited too. A compromised or careless admin session is
     * exactly the one with the most reach, and "trusted users are exempt" is how a control
     * ends up protecting only the people who were never the threat. The allowance is sized
     * so that a dashboard which refreshes and pages cannot reach it (see
     * {@code docs/rate-limiting.md}); it exists to bound a script, not to police a human.
     */
    ADMIN_READ("admin-read", Scope.USER, FailureMode.OPEN, true),

    /**
     * Administrative operations that change state, per administrator.
     *
     * <p>Separate from {@link #ADMIN_READ} because the two protect different things and
     * therefore want different answers when Redis is unavailable. A read is a cost
     * control, so it fails open — refusing an administrator a dashboard because the
     * limiter is down helps nobody. A write is a blast-radius control, and the operations
     * it covers are the ones that are expensive or irreversible: finalising a contest
     * computes ratings across the whole field and writes a permanent, append-only history
     * that no later call can undo.
     *
     * <p>So this one fails <b>closed</b>. If the limiter cannot answer, the honest
     * response is to refuse the write and let the administrator retry, rather than to
     * process an unbounded number of finalisation attempts with no control at all.
     *
     * <p>The allowance is small on purpose. These are operations a human performs a
     * handful of times; a caller reaching this limit is a script, and a script hammering
     * finalisation is the exact thing that turns a cheap idempotent no-op into a
     * sustained load on the standings query.
     */
    ADMIN_WRITE("admin-write", Scope.USER, FailureMode.CLOSED, true);

    /** What the bucket is keyed on. */
    public enum Scope {
        /** The authenticated user. Cardinality is bounded by the user table. */
        USER,
        /** The calling client, when there is no authenticated user yet. */
        CLIENT,
        /** The account an anonymous request is *about*, not the one making it. */
        ACCOUNT
    }

    /** What happens when Redis cannot answer. */
    public enum FailureMode {
        /** Refuse the request. For controls whose absence is a security hole. */
        CLOSED,
        /** Allow the request. For controls whose absence is merely a missing comfort. */
        OPEN
    }

    private final String id;
    private final Scope scope;
    private final FailureMode failureMode;
    private final boolean auditable;

    RateLimitPolicy(String id, Scope scope, FailureMode failureMode, boolean auditable) {
        this.id = id;
        this.scope = scope;
        this.failureMode = failureMode;
        this.auditable = auditable;
    }

    /** Stable, bounded, lower-case identifier used in Redis keys and metric tags. */
    public String id() {
        return id;
    }

    public Scope scope() {
        return scope;
    }

    public FailureMode failureMode() {
        return failureMode;
    }

    /** Whether a rejection may produce an audit event. See {@link RateLimitViolationAuditor}. */
    public boolean auditable() {
        return auditable;
    }
}
