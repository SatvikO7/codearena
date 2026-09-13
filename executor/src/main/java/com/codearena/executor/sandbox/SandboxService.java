package com.codearena.executor.sandbox;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionResult;

/**
 * Runs untrusted code somewhere it cannot do harm.
 *
 * <p>An interface so that the service exposing the HTTP contract depends on the capability
 * rather than on Docker. Replacing Docker with gVisor, a rootless daemon or Firecracker
 * later should not require touching the API layer.
 *
 * <p>A {@link Workspace} is a scope: it owns the temporary storage a submission's
 * compilation and runs share, and closing it destroys everything — containers, volumes,
 * compiled artefacts. It is {@link AutoCloseable} so cleanup is a language guarantee rather
 * than a convention somebody has to remember at every early return.
 */
public interface SandboxService {

    /**
     * Creates an isolated workspace and writes the source into it.
     *
     * @param submissionId used only to label resources for operators, never interpreted
     * @param source       untrusted; written to a fixed filename inside the sandbox
     */
    Workspace prepare(String submissionId, Language language, String source);

    /** A per-submission sandbox. Closing it must leave nothing behind. */
    interface Workspace extends AutoCloseable {

        /**
         * Builds the program, if the language needs it.
         *
         * <p>Compilation is itself the processing of untrusted input — a compiler can be
         * made to burn minutes of CPU and gigabytes of memory by source that never runs — so
         * it happens inside the same sandbox, under its own independent limits.
         *
         * @return the compiler's result; a non-zero exit is a compilation error, not a
         *         judge failure
         */
        ExecutionResult compile(ExecutionLimits limits);

        /**
         * Runs the program once against one test's input.
         *
         * <p>The input arrives on stdin. Nothing else about the test crosses into the
         * container — in particular the expected output never does, because a program that
         * can read the answer key can print it.
         */
        ExecutionResult run(String stdin, ExecutionLimits limits);

        /** Releases every resource. Safe to call more than once. */
        @Override
        void close();
    }

    /** The judge could not run the code. Distinct from the code running and failing. */
    class SandboxInfrastructureException extends RuntimeException {
        public SandboxInfrastructureException(String message) {
            super(message);
        }
    }
}
