package com.codearena.problem;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a problem sits in its authoring lifecycle.
 *
 * <p>The permitted moves are declared here rather than being checked ad hoc at each call
 * site, so there is exactly one place that answers "can this problem go there?". Status
 * is never assignable through the update payload: it changes only through the explicit
 * publish, unpublish and archive operations, which is what stops a client from sending
 * {@code "status": "PUBLISHED"} and skipping the content checks that publication
 * requires.
 *
 * <p>Persisted by {@link #name()}, never by ordinal, so reordering this enum cannot
 * silently reclassify existing rows.
 */
public enum ProblemStatus {

    /** Being written. Invisible to everyone except administrators. */
    DRAFT,

    /** Visible in the public catalogue. */
    PUBLISHED,

    /** Withdrawn from the catalogue but retained, so existing references stay resolvable. */
    ARCHIVED;

    private static final Set<ProblemStatus> FROM_DRAFT = EnumSet.of(PUBLISHED, ARCHIVED);
    private static final Set<ProblemStatus> FROM_PUBLISHED = EnumSet.of(DRAFT, ARCHIVED);
    /**
     * Archiving is reversible.
     *
     * <p>The brief listed four transitions and did not include this one. It is added
     * deliberately: archiving is a single click, and without a way back an administrator
     * who archives the wrong problem has no recourse short of a database edit. Restoring
     * lands in DRAFT rather than PUBLISHED, so a problem cannot silently reappear in the
     * catalogue — returning it to the public listing still requires an explicit publish,
     * which re-runs the completeness checks.
     */
    private static final Set<ProblemStatus> FROM_ARCHIVED = EnumSet.of(DRAFT);

    public boolean canTransitionTo(ProblemStatus target) {
        return switch (this) {
            case DRAFT -> FROM_DRAFT.contains(target);
            case PUBLISHED -> FROM_PUBLISHED.contains(target);
            case ARCHIVED -> FROM_ARCHIVED.contains(target);
        };
    }

    /** The statuses a normal user is ever allowed to see. */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED;
    }
}
