package com.codearena.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionStatusTest {

    @ParameterizedTest
    @CsvSource({
            "QUEUED,  RUNNING",
            "RUNNING, ACCEPTED",
            "RUNNING, WRONG_ANSWER",
            "RUNNING, COMPILATION_ERROR",
            "RUNNING, RUNTIME_ERROR",
            "RUNNING, TIME_LIMIT_EXCEEDED",
            "RUNNING, MEMORY_LIMIT_EXCEEDED",
            "RUNNING, SYSTEM_ERROR",
    })
    void allowsTheDefinedForwardTransitions(SubmissionStatus from, SubmissionStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    /**
     * The one backwards move, and the reason it exists: a worker that dies holding a claim
     * would otherwise strand its submission in RUNNING for ever.
     */
    @Test
    void allowsRunningBackToQueuedForCrashRecovery() {
        assertThat(SubmissionStatus.RUNNING.canTransitionTo(SubmissionStatus.QUEUED)).isTrue();
    }

    /**
     * The property the whole pipeline depends on: once a verdict is recorded, nothing can
     * change it. A straggling worker finishing late must not be able to overwrite it.
     */
    @ParameterizedTest
    @EnumSource(SubmissionStatus.class)
    void nothingEverLeavesATerminalState(SubmissionStatus target) {
        for (SubmissionStatus terminal : SubmissionStatus.terminalStates()) {
            assertThat(terminal.canTransitionTo(target))
                    .as("%s must not transition to %s", terminal, target)
                    .isFalse();
        }
    }

    @Test
    void queuedCannotJumpStraightToAVerdict() {
        assertThat(SubmissionStatus.QUEUED.canTransitionTo(SubmissionStatus.ACCEPTED)).isFalse();
        assertThat(SubmissionStatus.QUEUED.canTransitionTo(SubmissionStatus.WRONG_ANSWER)).isFalse();
        assertThat(SubmissionStatus.QUEUED.canTransitionTo(SubmissionStatus.QUEUED)).isFalse();
    }

    @Test
    void classifiesTerminalStatesCorrectly() {
        assertThat(SubmissionStatus.QUEUED.isTerminal()).isFalse();
        assertThat(SubmissionStatus.RUNNING.isTerminal()).isFalse();
        assertThat(SubmissionStatus.terminalStates()).hasSize(7);
        assertThat(SubmissionStatus.terminalStates())
                .allSatisfy(status -> assertThat(status.isTerminal()).isTrue());
    }

    /** Persisted by name; reordering must not reinterpret stored verdicts. */
    @ParameterizedTest
    @EnumSource(SubmissionStatus.class)
    void persistsByName(SubmissionStatus status) {
        assertThat(SubmissionStatus.valueOf(status.name())).isEqualTo(status);
    }
}
