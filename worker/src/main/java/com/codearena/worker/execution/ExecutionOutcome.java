package com.codearena.worker.execution;

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

    /** The judge itself failed: Docker unreachable, image missing, workspace unusable. */
    INFRASTRUCTURE_FAILURE
}
