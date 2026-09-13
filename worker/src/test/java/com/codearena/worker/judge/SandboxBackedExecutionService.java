package com.codearena.worker.judge;

import com.codearena.executor.sandbox.SandboxService;
import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionResult;
import com.codearena.worker.execution.ExecutionService;

import java.nio.file.Path;

/**
 * Lets a worker test drive a real sandbox directly.
 *
 * <p>In production the worker reaches the sandbox over HTTP and never links against the
 * execution service at all. {@link JudgeVerdictIT} is asserting how judging maps real
 * execution outcomes to verdicts, and that question is about the mapping rather than the
 * transport — so the test skips the HTTP hop and keeps the containers real. The transport's
 * own failure behaviour is covered separately by {@code RemoteExecutionServiceTest}.
 */
final class SandboxBackedExecutionService implements ExecutionService {

    private final SandboxService delegate;

    SandboxBackedExecutionService(SandboxService delegate) {
        this.delegate = delegate;
    }

    /**
     * The seccomp profile, as an absolute path the Docker CLI can read.
     *
     * <p>The CLI reads this file itself and embeds the JSON in the container
     * configuration, so it is resolved against the working tree rather than against
     * anything inside a container.
     */
    static String seccompProfilePath() {
        return Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .getParent()
                .resolve("sandbox")
                .resolve("seccomp")
                .resolve("codearena.json")
                .toString();
    }

    @Override
    public Workspace prepare(String submissionId, Language language, String source) {
        SandboxService.Workspace workspace = delegate.prepare(submissionId, language, source);
        return new Workspace() {
            @Override
            public ExecutionResult compile(ExecutionLimits limits) {
                return workspace.compile(limits);
            }

            @Override
            public ExecutionResult run(String stdin, ExecutionLimits limits) {
                return workspace.run(stdin, limits);
            }

            @Override
            public void close() {
                workspace.close();
            }
        };
    }
}
