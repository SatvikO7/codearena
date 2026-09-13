package com.codearena.worker.execution;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionResult;

/**
 * Runs untrusted code somewhere it cannot do harm.
 *
 * <p>An interface so the judge depends on the capability rather than on Docker. The judge
 * decides verdicts; how a program is isolated is somebody else's problem, and replacing
 * Docker with gVisor, Firecracker or a remote execution service later should not require
 * touching a line of judging logic.
 *
 * <p>A {@link Workspace} is a scope: it owns the temporary storage a submission's
 * compilation and runs share, and closing it destroys everything — containers, volumes,
 * compiled artefacts. It is {@link AutoCloseable} so that cleanup is a language guarantee
 * rather than a convention somebody has to remember at every early return.
 */
public interface ExecutionService {

    /**
     * Creates an isolated workspace and writes the source into it.
     *
     * @param submissionId used only to name resources for debugging, never interpreted
     * @param source       untrusted; written to a fixed filename inside the sandbox
     */
    Workspace prepare(String submissionId, Language language, String source);

    /** A per-submission sandbox. Closing it must leave nothing behind. */
    interface Workspace extends AutoCloseable {

        /**
         * Builds the program, if the language needs it.
         *
         * @return the compiler's result; a non-zero exit is a COMPILATION_ERROR, not a
         *         judge failure
         */
        ExecutionResult compile(ExecutionLimits limits);

        /**
         * Runs the program once against one test's input.
         *
         * <p>The input is fed on stdin. Nothing else about the test crosses into the
         * container — in particular the expected output never does, because a program that
         * can read the answer key can print it.
         */
        ExecutionResult run(String stdin, ExecutionLimits limits);

        /** Releases every resource. Safe to call more than once. */
        @Override
        void close();
    }

    /**
     * The sandbox could not be obtained, and trying again later is likely to work.
     *
     * <p>Distinct from every other failure because the right response is different. A
     * program that crashes has been judged; a sandbox that could not be created has not.
     * The execution service refuses work when it is already running as much as it is
     * configured to run, and a machine being briefly busy must not permanently fail
     * somebody's submission.
     *
     * <p>Throwing this leaves the submission claimed but unfinished, so the existing
     * recovery sweeper reclaims it once the lease expires and it is judged again — bounded
     * by {@code attempts}, which was incremented when it was claimed. After the last attempt
     * the sweeper records SYSTEM_ERROR, so a genuinely broken executor still terminates
     * rather than looping for ever.
     */
    class ExecutionUnavailableException extends RuntimeException {
        public ExecutionUnavailableException(String message) {
            super(message);
        }
    }
}
