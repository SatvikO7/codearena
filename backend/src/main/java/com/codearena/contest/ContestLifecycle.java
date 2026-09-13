package com.codearena.contest;

/**
 * What a human decided about a contest. Deliberately <em>not</em> what users see.
 *
 * <p>The observable status — UPCOMING, LIVE, ENDED — is derived from this plus the schedule
 * and the current time, and is modelled by {@link ContestStatus}. This enum holds only the
 * three things an administrator can actually choose, which is why there are three constants
 * and not five.
 *
 * <p>See ADR-032 for why the visible status is computed rather than stored.
 */
public enum ContestLifecycle {

    /**
     * Being written. Invisible to everyone but administrators, and freely editable.
     */
    DRAFT,

    /**
     * Released. Visible to users, and its schedule and problem set are settled — though
     * both remain editable until the contest actually starts.
     */
    PUBLISHED,

    /**
     * Called off. Terminal: a cancelled contest is never resurrected, because participants
     * have already been told it is not happening.
     */
    CANCELLED;

    /**
     * Whether an administrator may move a contest from this state to {@code target}.
     *
     * <p>The permitted moves are deliberately few:
     * <ul>
     *   <li>DRAFT → PUBLISHED — release it.</li>
     *   <li>DRAFT → CANCELLED — abandon something never released.</li>
     *   <li>PUBLISHED → CANCELLED — call off a released contest. Whether that is allowed
     *       once it has started is a separate question, answered by
     *       {@link Contest#cancel}, because it depends on the clock rather than on this
     *       value.</li>
     *   <li>PUBLISHED → DRAFT — <b>refused</b>. Un-publishing a contest people can already
     *       see, and may have registered for, is not an edit; it is a cancellation wearing
     *       a disguise.</li>
     * </ul>
     */
    public boolean canTransitionTo(ContestLifecycle target) {
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == CANCELLED;
            case PUBLISHED -> target == CANCELLED;
            case CANCELLED -> false;
        };
    }

    /** Whether users other than administrators may see a contest in this state at all. */
    public boolean isPubliclyVisible() {
        return this != DRAFT;
    }
}
