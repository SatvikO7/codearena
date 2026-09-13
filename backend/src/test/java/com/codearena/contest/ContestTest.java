package com.codearena.contest;

import com.codearena.common.ValidationException;
import com.codearena.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules the {@link Contest} aggregate refuses to break.
 *
 * <p>These live on the entity rather than in a service precisely so that they hold for any
 * caller. A service can be bypassed by a new endpoint written by somebody who did not know
 * the rule; an aggregate that throws cannot be.
 */
class ContestTest {

    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-03-01T13:00:00Z");
    private static final Instant BEFORE = START.minusSeconds(3600);
    private static final Instant DURING = START.plusSeconds(60);
    private static final Instant AFTER = END.plusSeconds(60);

    private static Contest draft() {
        return Contest.create("Spring Contest", "spring-2026", "Three hours.", START, END, user());
    }

    private static User user() {
        // A detached User is enough: the aggregate never dereferences it for these rules.
        return User.create("admin", "admin@codearena.dev", "hashed", com.codearena.user.Role.ADMIN);
    }

    // ------------------------------------------------------------------ schedule

    @Test
    void refusesAContestThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> Contest.create("Backwards", "backwards", null, END, START, user()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must end after it starts");
    }

    @Test
    void refusesAContestThatEndsExactlyWhenItStarts() {
        assertThatThrownBy(() -> Contest.create("Instant", "instant", null, START, START, user()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refusesAnImplausiblyShortContest() {
        assertThatThrownBy(() ->
                Contest.create("Blink", "blink", null, START, START.plusSeconds(60), user()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least");
    }

    /** Catches a mistyped year, which would otherwise be LIVE for a decade. */
    @Test
    void refusesAContestLongerThanTheCeiling() {
        assertThatThrownBy(() ->
                Contest.create("Forever", "forever", null, START, START.plusSeconds(86400L * 30), user()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void acceptsASensibleSchedule() {
        assertThatCode(ContestTest::draft).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ publishing

    @Test
    void refusesToPublishAContestWithNoProblems() {
        assertThatThrownBy(() -> draft().publish(BEFORE))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one problem");
    }

    @Test
    void refusesToPublishAContestThatHasAlreadyEnded() {
        Contest contest = draft();
        contest.addProblem(null, BEFORE);   // presence is all publish() checks

        assertThatThrownBy(() -> contest.publish(AFTER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already ended");
    }

    @Test
    void publishesADraftThatHasProblems() {
        Contest contest = draft();
        contest.addProblem(null, BEFORE);

        contest.publish(BEFORE);

        assertThat(contest.getLifecycle()).isEqualTo(ContestLifecycle.PUBLISHED);
        assertThat(contest.statusAt(BEFORE)).isEqualTo(ContestStatus.UPCOMING);
        assertThat(contest.statusAt(DURING)).isEqualTo(ContestStatus.LIVE);
    }

    // ------------------------------------------------------------------ the freeze

    @Test
    void allowsEditingWhileUpcoming() {
        Contest contest = published();

        assertThatCode(() -> contest.reschedule(START.plusSeconds(600), END, BEFORE))
                .doesNotThrowAnyException();
    }

    /**
     * The rule the whole phase turns on. Moving the end time would invalidate every penalty
     * already computed; changing points would rewrite the standings of everyone who already
     * solved the problem.
     */
    @Test
    void refusesToRescheduleALiveContest() {
        Contest contest = published();

        assertThatThrownBy(() -> contest.reschedule(START, END.plusSeconds(3600), DURING))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be modified");
    }

    @Test
    void refusesToChangeAnEndedContest() {
        Contest contest = published();

        assertThatThrownBy(() -> contest.updateDetails("New", "new", null, AFTER))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> contest.requireEditable(AFTER))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refusesToAddOrRemoveProblemsOnceLive() {
        Contest contest = published();

        assertThatThrownBy(() -> contest.addProblem(null, DURING))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> contest.removeProblem(null, DURING))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ cancelling

    /**
     * Cancelling a running contest is permitted, deliberately. A broken problem or a leaked
     * test set is a real reason to stop one, and refusing would leave no honest option but to
     * let a spoiled contest finish.
     */
    @Test
    void allowsCancellingALiveContest() {
        Contest contest = published();

        contest.cancel(DURING);

        assertThat(contest.statusAt(DURING)).isEqualTo(ContestStatus.CANCELLED);
        assertThat(contest.acceptsSubmissionAt(DURING)).isFalse();
    }

    /** An ended contest's result is already history. */
    @Test
    void refusesToCancelAnEndedContest() {
        Contest contest = published();

        assertThatThrownBy(() -> contest.cancel(AFTER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already ended");
    }

    @Test
    void refusesToCancelTwice() {
        Contest contest = published();
        contest.cancel(DURING);

        assertThatThrownBy(() -> contest.cancel(DURING))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ the window

    @Test
    void acceptsSubmissionsOnlyInsideTheHalfOpenWindow() {
        Contest contest = published();

        assertThat(contest.acceptsSubmissionAt(START.minusMillis(1))).isFalse();
        assertThat(contest.acceptsSubmissionAt(START)).isTrue();
        assertThat(contest.acceptsSubmissionAt(END.minusMillis(1))).isTrue();
        assertThat(contest.acceptsSubmissionAt(END)).isFalse();
    }

    @Test
    void neverAcceptsSubmissionsForADraft() {
        assertThat(draft().acceptsSubmissionAt(DURING)).isFalse();
    }

    private static Contest published() {
        Contest contest = draft();
        contest.addProblem(null, BEFORE);
        contest.publish(BEFORE);
        return contest;
    }
}
