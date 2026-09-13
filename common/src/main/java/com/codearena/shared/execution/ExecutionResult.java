package com.codearena.shared.execution;

/**
 * The structured result of one sandboxed run.
 *
 * @param outcome    what happened
 * @param exitCode   process exit code, or -1 when the container was killed before exiting
 * @param stdout     captured standard output, already truncated to the output limit
 * @param stderr     captured standard error, already truncated
 * @param durationMs wall-clock duration measured by the worker
 * @param detail     a short explanation for infrastructure failures; never shown verbatim
 *                   to a user without sanitisation
 */
public record ExecutionResult(
        ExecutionOutcome outcome,
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        String detail) {

    public static ExecutionResult completed(int exitCode, String stdout, String stderr, long durationMs) {
        return new ExecutionResult(
                exitCode == 0 ? ExecutionOutcome.COMPLETED : ExecutionOutcome.NON_ZERO_EXIT,
                exitCode, stdout, stderr, durationMs, null);
    }

    public static ExecutionResult killed(ExecutionOutcome outcome, String stdout, String stderr, long durationMs) {
        return new ExecutionResult(outcome, -1, stdout, stderr, durationMs, null);
    }

    public static ExecutionResult infrastructureFailure(String detail) {
        return new ExecutionResult(
                ExecutionOutcome.INFRASTRUCTURE_FAILURE, -1, "", "", 0, detail);
    }

    public boolean succeeded() {
        return outcome == ExecutionOutcome.COMPLETED;
    }
}
