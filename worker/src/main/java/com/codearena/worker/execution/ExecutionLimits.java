package com.codearena.worker.execution;

/**
 * The budget a single sandboxed run is allowed.
 *
 * <p>Every one of these is enforced by the container runtime, not measured afterwards.
 * Measuring after the fact is useless against the programs that matter: an infinite loop
 * never finishes to be measured, and a runaway allocation takes the host down before any
 * bookkeeping runs.
 *
 * @param wallClockMillis hard timeout. The container is killed when it expires.
 * @param memoryMb        container memory ceiling; exceeding it is an OOM kill by the kernel
 * @param cpus            fractional CPU quota, so one submission cannot monopolise a core
 * @param pids            maximum processes, which is what stops a fork bomb
 * @param outputBytes     maximum stdout+stderr captured before the run is abandoned
 */
public record ExecutionLimits(
        long wallClockMillis,
        int memoryMb,
        double cpus,
        int pids,
        int outputBytes) {

    public ExecutionLimits {
        if (wallClockMillis <= 0 || memoryMb <= 0 || cpus <= 0 || pids <= 0 || outputBytes <= 0) {
            throw new IllegalArgumentException("Execution limits must all be positive");
        }
    }

    /**
     * Compilation gets its own budget, independent of the problem's.
     *
     * <p>A problem's one-second run limit says nothing about how long g++ may take, and a
     * compiler is also an attack surface: template metaprogramming can burn minutes of CPU
     * and gigabytes of memory without executing a line of the program. It gets a generous
     * but finite allowance.
     */
    public ExecutionLimits forCompilation(long compileMillis, int compileMemoryMb) {
        return new ExecutionLimits(compileMillis, compileMemoryMb, cpus, pids, outputBytes);
    }
}
