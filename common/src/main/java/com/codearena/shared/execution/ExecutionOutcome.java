package com.codearena.shared.execution;

/**
 * How a sandboxed run ended.
 *
 * <p>Kept separate from the submission's verdict on purpose. This describes what the
 * container did; the judge decides what that means. "Exited cleanly" is not yet
 * ACCEPTED — the output still has to match.
 */
public enum ExecutionOutcome {

    /** Ran to completion with exit code 0. */
    COMPLETED,

    /** Exited non-zero, or was killed by a signal. */
    NON_ZERO_EXIT,

    /** Killed because it exceeded its wall-clock budget. */
    TIMED_OUT,

    /** Killed by the kernel for exceeding the container memory limit. */
    OUT_OF_MEMORY,

    /** Killed because it wrote more output than the limit allows. */
    OUTPUT_LIMIT_EXCEEDED,

    /**
     * Killed by SIGXFSZ: it tried to write a file larger than {@code RLIMIT_FSIZE}.
     *
     * <p>Kept distinct from a plain crash because the cause is specific and worth telling
     * the submitter — a program that dies this way is writing far more to disk than any
     * solution needs, and "exited with signal 25" explains nothing.
     */
    FILE_LIMIT_EXCEEDED,

    /** The judge itself failed: Docker unreachable, image missing, workspace unusable. */
    INFRASTRUCTURE_FAILURE
}
