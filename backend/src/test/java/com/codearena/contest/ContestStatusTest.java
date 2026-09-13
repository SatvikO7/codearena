package com.codearena.contest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contest clock, at the boundaries.
 *
 * <p>These are the tests that matter most in the phase, and they are cheap only because the
 * status is a pure function. Asking "what is this contest at exactly {@code endAt}?" of a
 * system that reads {@code Instant.now()} means either waiting for a real contest to end or
 * asserting something vaguer nearby; here it is an equality check.
 */
class ContestStatusTest {

    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-03-01T13:00:00Z");

    private static ContestStatus at(Instant when) {
        return ContestStatus.of(ContestLifecycle.PUBLISHED, START, END, when);
    }

    // ------------------------------------------------------------- the boundaries

    @Test
    void isUpcomingWellBeforeTheStart() {
        assertThat(at(START.minusSeconds(3600))).isEqualTo(ContestStatus.UPCOMING);
    }

    @Test
    void isStillUpcomingOneMillisecondBeforeTheStart() {
        assertThat(at(START.minusMillis(1))).isEqualTo(ContestStatus.UPCOMING);
    }

    /** The window is half-open at the bottom: startAt itself is inside the contest. */
    @Test
    void isLiveAtExactlyTheStart() {
        assertThat(at(START)).isEqualTo(ContestStatus.LIVE);
    }

    @Test
    void isLiveOneMillisecondAfterTheStart() {
        assertThat(at(START.plusMillis(1))).isEqualTo(ContestStatus.LIVE);
    }

    @Test
    void isLiveOneMillisecondBeforeTheEnd() {
        assertThat(at(END.minusMillis(1))).isEqualTo(ContestStatus.LIVE);
    }

    /**
     * And half-open at the top: endAt belongs to ENDED.
     *
     * <p>The alternative — an inclusive end — leaves one instant at which the contest is both
     * running and finished. That ambiguity is only ever discovered by the person whose
     * submission lands on it.
     */
    @Test
    void isEndedAtExactlyTheEnd() {
        assertThat(at(END)).isEqualTo(ContestStatus.ENDED);
    }

    @Test
    void isEndedOneMillisecondAfterTheEnd() {
        assertThat(at(END.plusMillis(1))).isEqualTo(ContestStatus.ENDED);
    }

    /** Every instant belongs to exactly one state. There is no gap and no overlap. */
    @Test
    void everyInstantHasExactlyOneStatus() {
        Instant[] probes = {
                START.minusSeconds(1), START, START.plusSeconds(1),
                END.minusSeconds(1), END, END.plusSeconds(1)
        };
        for (Instant probe : probes) {
            ContestStatus status = at(probe);
            assertThat(status).as("status at %s", probe).isNotNull();
            // Exactly one of the three published states, never two.
            long matches = java.util.stream.Stream.of(
                            ContestStatus.UPCOMING, ContestStatus.LIVE, ContestStatus.ENDED)
                    .filter(candidate -> candidate == status)
                    .count();
            assertThat(matches).as("status at %s is exactly one state", probe).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------- lifecycle wins

    /** A draft is a draft whatever the clock says. The schedule is not consulted. */
    @Test
    void aDraftIsNeverLiveHoweverItIsScheduled() {
        assertThat(ContestStatus.of(ContestLifecycle.DRAFT, START, END, START.plusSeconds(60)))
                .isEqualTo(ContestStatus.DRAFT);
        assertThat(ContestStatus.of(ContestLifecycle.DRAFT, START, END, END.plusSeconds(60)))
                .isEqualTo(ContestStatus.DRAFT);
    }

    /** Cancelling stops a contest immediately, mid-window included. */
    @Test
    void aCancelledContestIsNeverLive() {
        assertThat(ContestStatus.of(ContestLifecycle.CANCELLED, START, END, START.plusSeconds(60)))
                .isEqualTo(ContestStatus.CANCELLED);
    }

    // ------------------------------------------------------------- what each permits

    @Test
    void onlyALiveContestAcceptsSubmissions() {
        for (ContestStatus status : ContestStatus.values()) {
            assertThat(status.acceptsSubmissions())
                    .as("%s accepts submissions", status)
                    .isEqualTo(status == ContestStatus.LIVE);
        }
    }

    @Test
    void onlyAnUpcomingContestAcceptsRegistration() {
        for (ContestStatus status : ContestStatus.values()) {
            assertThat(status.acceptsRegistration())
                    .as("%s accepts registration", status)
                    .isEqualTo(status == ContestStatus.UPCOMING);
        }
    }

    /** Editing stops exactly when competing starts. */
    @Test
    void onlyADraftOrUpcomingContestIsEditable() {
        assertThat(ContestStatus.DRAFT.isEditable()).isTrue();
        assertThat(ContestStatus.UPCOMING.isEditable()).isTrue();
        assertThat(ContestStatus.LIVE.isEditable()).isFalse();
        assertThat(ContestStatus.ENDED.isEditable()).isFalse();
        assertThat(ContestStatus.CANCELLED.isEditable()).isFalse();
    }

    // ------------------------------------------------------------- timezone-independence

    /**
     * The same instant expressed in different zones is the same instant.
     *
     * <p>A contest scheduled by an administrator in IST and read by a contestant in UTC must
     * be the same contest. It is, because nothing here holds a zone: the comparison is
     * between instants, and the offset is a rendering concern.
     */
    @Test
    void treatsTheSameInstantIdenticallyHoweverItIsWritten() {
        Instant utc = Instant.parse("2026-03-01T10:00:00Z");
        Instant ist = Instant.parse("2026-03-01T15:30:00+05:30");
        Instant newYork = Instant.parse("2026-03-01T05:00:00-05:00");

        assertThat(ist).isEqualTo(utc);
        assertThat(newYork).isEqualTo(utc);

        assertThat(ContestStatus.of(ContestLifecycle.PUBLISHED, utc, END, ist))
                .isEqualTo(ContestStatus.LIVE);
        assertThat(ContestStatus.of(ContestLifecycle.PUBLISHED, ist, END, newYork))
                .isEqualTo(ContestStatus.LIVE);
    }

    // ------------------------------------------------------------- lifecycle moves

    @Test
    void allowsOnlyTheIntendedLifecycleMoves() {
        assertThat(ContestLifecycle.DRAFT.canTransitionTo(ContestLifecycle.PUBLISHED)).isTrue();
        assertThat(ContestLifecycle.DRAFT.canTransitionTo(ContestLifecycle.CANCELLED)).isTrue();
        assertThat(ContestLifecycle.PUBLISHED.canTransitionTo(ContestLifecycle.CANCELLED)).isTrue();
    }

    /**
     * Un-publishing is refused. It is a cancellation in disguise: people can already see the
     * contest and may have registered for it.
     */
    @Test
    void refusesToUnpublish() {
        assertThat(ContestLifecycle.PUBLISHED.canTransitionTo(ContestLifecycle.DRAFT)).isFalse();
    }

    /** Cancelled is terminal. A contestant is never told a contest is off and then on again. */
    @Test
    void neverLeavesCancelled() {
        for (ContestLifecycle target : ContestLifecycle.values()) {
            assertThat(ContestLifecycle.CANCELLED.canTransitionTo(target))
                    .as("CANCELLED -> %s", target)
                    .isFalse();
        }
    }

    @Test
    void hidesOnlyDraftsFromUsers() {
        assertThat(ContestLifecycle.DRAFT.isPubliclyVisible()).isFalse();
        assertThat(ContestLifecycle.PUBLISHED.isPubliclyVisible()).isTrue();
        // Cancelled stays visible: people registered for it and are entitled to see why it
        // is not happening.
        assertThat(ContestLifecycle.CANCELLED.isPubliclyVisible()).isTrue();
    }
}
