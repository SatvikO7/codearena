package com.codearena.contest;

import java.time.Instant;

/**
 * The status a user sees. Computed, never stored.
 *
 * <h2>The rule, stated once</h2>
 * Given a {@link ContestLifecycle}, a schedule and an instant:
 *
 * <pre>
 *   DRAFT      → DRAFT
 *   CANCELLED  → CANCELLED
 *   PUBLISHED  → UPCOMING  when  now &lt;  startAt
 *              → LIVE      when  startAt &lt;= now &lt;  endAt
 *              → ENDED     when  endAt   &lt;= now
 * </pre>
 *
 * <h2>The window is half-open: {@code [startAt, endAt)}</h2>
 * At exactly {@code startAt} a contest is LIVE. At exactly {@code endAt} it is ENDED and
 * submissions are refused. Every instant belongs to exactly one state — there is no moment
 * that is both, and none that is neither. An inclusive end would leave a single ambiguous
 * millisecond in which a contest is simultaneously running and finished, which is the sort
 * of thing that is only ever discovered by the person it costs a problem.
 *
 * <h2>Why this is not a database column</h2>
 * A stored status has to be advanced by something. If that something is down at
 * {@code endAt}, is late, or the clock skews, the row says LIVE after the contest is over —
 * and a late submission is accepted because a background job had not got round to it.
 * Deriving the status means a contest ends on time whether or not anything is running to
 * notice, and a restart cannot resurrect a finished one. ADR-032.
 */
public enum ContestStatus {

    /** Being written. Administrators only. */
    DRAFT,

    /** Published, not yet started. Registration is open. */
    UPCOMING,

    /** Running now. Registered users may submit. */
    LIVE,

    /** Finished. Standings are final; submissions are refused. */
    ENDED,

    /** Called off. Terminal. */
    CANCELLED;

    /**
     * Derives the visible status.
     *
     * @param at the instant to evaluate against — the caller's clock, so that tests can
     *           ask "what is this contest at exactly {@code endAt}?" without waiting
     */
    public static ContestStatus of(ContestLifecycle lifecycle, Instant startAt, Instant endAt, Instant at) {
        return switch (lifecycle) {
            case DRAFT -> DRAFT;
            case CANCELLED -> CANCELLED;
            case PUBLISHED -> {
                if (at.isBefore(startAt)) {
                    yield UPCOMING;
                }
                // isBefore, not !isAfter: endAt itself belongs to ENDED.
                yield at.isBefore(endAt) ? LIVE : ENDED;
            }
        };
    }

    /** Whether submissions may be accepted. Only ever true for LIVE. */
    public boolean acceptsSubmissions() {
        return this == LIVE;
    }

    /**
     * Whether registration is open.
     *
     * <p>Only while UPCOMING: registration closes the moment a contest starts. Letting
     * people join a contest already in progress means they compete over a shorter window
     * against the same clock, and the standings stop comparing like with like. Allowing it
     * would need a per-participant start time and a different penalty basis, which is a
     * different product decision rather than a looser check. ADR-033.
     */
    public boolean acceptsRegistration() {
        return this == UPCOMING;
    }

    /** Whether the schedule and problem set are still editable. */
    public boolean isEditable() {
        return this == DRAFT || this == UPCOMING;
    }

    /** Whether the contest has begun, and so has a scoring history worth preserving. */
    public boolean hasStarted() {
        return this == LIVE || this == ENDED;
    }
}
