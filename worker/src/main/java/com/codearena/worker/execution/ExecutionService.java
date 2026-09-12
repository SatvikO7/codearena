package com.codearena.worker.execution;

import com.codearena.shared.Language;

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
}
