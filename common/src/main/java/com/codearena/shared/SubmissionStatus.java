package com.codearena.shared;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a submission is in its judging lifecycle.
 *
 * <p>Lives in the shared module because both deployables enforce it: the API server
 * refuses illegal transitions when it accepts or recovers a submission, and the worker
 * refuses them when it records a result. A rule that only one of them knew would be a rule
 * the other could violate.
 *
 * <p>Persisted by {@link #name()}, never by ordinal, so reordering this enum cannot
 * silently reinterpret stored verdicts.
 */
public enum SubmissionStatus {

    /** Accepted by the API and waiting for a worker. */
    QUEUED(false),

    /** Claimed by a worker; compiling or executing. */
    RUNNING(false),

    /** Every test case passed. */
    ACCEPTED(true),

    /** The program ran but produced the wrong output for at least one test. */
    WRONG_ANSWER(true),

    /** The source did not compile. */
    COMPILATION_ERROR(true),

    /** The program terminated abnormally — a non-zero exit, a signal, an uncaught exception. */
    RUNTIME_ERROR(true),

    /** The program exceeded the problem's wall-clock budget. */
    TIME_LIMIT_EXCEEDED(true),

    /** The program exceeded the problem's memory budget. */
    MEMORY_LIMIT_EXCEEDED(true),

    /**
     * The judge itself failed: Docker was unreachable, the workspace could not be
     * created, retries were exhausted. Never the submitted program's fault, which is why
     * it is the only terminal state that says nothing about the code.
     */
    SYSTEM_ERROR(true);

    private final boolean terminal;

    SubmissionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** A terminal status is final: nothing may move a submission out of it. */
    public boolean isTerminal() {
        return terminal;
    }

    /** The verdicts a judged run can end in. */
    public static Set<SubmissionStatus> terminalStates() {
        return EnumSet.of(ACCEPTED, WRONG_ANSWER, COMPILATION_ERROR, RUNTIME_ERROR,
                TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, SYSTEM_ERROR);
    }

    /**
     * Whether a submission may move from this status to {@code target}.
     *
     * <p>The rules:
     * <ul>
     *   <li>QUEUED → RUNNING, when a worker claims it.</li>
     *   <li>RUNNING → any terminal state, when judging finishes.</li>
     *   <li>RUNNING → QUEUED, and <em>only</em> this, when the recovery sweeper finds a
     *       worker died holding the claim. Without it a crashed worker would strand the
     *       submission in RUNNING forever. It is a step backwards, but only out of a
     *       non-terminal state, so no verdict is ever undone.</li>
     *   <li>Nothing leaves a terminal state, ever.</li>
     * </ul>
     */
    public boolean canTransitionTo(SubmissionStatus target) {
        if (terminal) {
            return false;
        }
        return switch (this) {
            case QUEUED -> target == RUNNING;
            case RUNNING -> target.isTerminal() || target == QUEUED;
            default -> false;
        };
    }
}
