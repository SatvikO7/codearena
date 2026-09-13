package com.codearena.audit;

/**
 * Who performed an audited action.
 *
 * <p>Derived on the server from the security context, never from a request. There is no
 * field anywhere in the API through which a caller can say who they are.
 */
public enum ActorType {

    /** An authenticated non-administrator. */
    USER,

    /**
     * An authenticated administrator.
     *
     * <p>Distinguished from USER because "what have the administrators been doing" is the
     * question this log exists to answer, and a role stored alongside the event stays true
     * even if the account's role changes afterwards.
     */
    ADMIN,

    /**
     * The application itself, acting without a user.
     *
     * <p>For events that have no human behind them — a sweeper abandoning a submission, a
     * scheduled task. Not used to disguise a user's action as the system's.
     */
    SYSTEM,

    /**
     * No session.
     *
     * <p>Chiefly failed logins, where there is by definition nobody authenticated yet. The
     * actor id is null; the identifier that was <em>attempted</em> lives in metadata, which
     * is a different claim and is recorded as one.
     */
    ANONYMOUS
}
