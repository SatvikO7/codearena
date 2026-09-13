package com.codearena.shared.execution;

import com.codearena.shared.Language;

/**
 * The wire contract between the judge worker and the execution service.
 *
 * <h2>Why this is deliberately narrow</h2>
 * The execution service is the only component in CodeArena that holds Docker control, and
 * this is the whole of what it will do on anyone's behalf. There is no field here for an
 * image, a mount, a capability, a network, a user, an entrypoint or a device — a caller can
 * ask for "compile and run this source, in one of three languages, within these limits" and
 * cannot express anything else.
 *
 * <p>That is the point of the boundary. A compromised worker inherits the authority this
 * contract describes, not the authority of the Docker socket. {@code Language} is an enum,
 * so the image is chosen by the executor from a fixed table; {@link ExecutionLimits} is
 * clamped server-side, so a caller asking for 64&nbsp;GB gets the configured maximum rather
 * than 64&nbsp;GB.
 *
 * <p>See ADR-028 and docs/threat-model.md.
 */
public final class ExecutionApi {

    /** Header carrying the shared secret. Absent or wrong means 401, before any parsing. */
    public static final String TOKEN_HEADER = "X-CodeArena-Executor-Token";

    private ExecutionApi() {
    }

    /**
     * Asks for a sandbox workspace holding this source.
     *
     * @param submissionId used only to label resources for operators; never interpreted,
     *                     never placed on a command line
     * @param language     selects the image from a fixed table on the executor
     * @param source       untrusted; written to a fixed filename inside the sandbox
     */
    public record PrepareRequest(String submissionId, Language language, String source) {
    }

    /**
     * The handle for a prepared workspace.
     *
     * <p>An opaque server-generated id. It is not a path, a container name or a volume name,
     * so a caller cannot address a resource the executor did not create for it.
     */
    public record PrepareResponse(String workspaceId) {
    }

    /** Compiles the prepared source, if the language needs compiling. */
    public record CompileRequest(ExecutionLimits limits) {
    }

    /**
     * Runs the prepared program once.
     *
     * @param stdin the test's input. The expected output is deliberately not part of this
     *              contract: it never leaves the worker, so a program that could read its
     *              own environment still cannot read the answer key.
     */
    public record RunRequest(String stdin, ExecutionLimits limits) {
    }
}
