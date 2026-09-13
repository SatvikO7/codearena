package com.codearena.executor.api;

import com.codearena.executor.sandbox.SandboxMetrics;
import com.codearena.executor.sandbox.SandboxPolicy;
import com.codearena.executor.sandbox.SandboxService;
import com.codearena.shared.execution.ExecutionApi;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The entire authority this service grants anyone.
 *
 * <p>Four operations: make me a workspace for one of three languages, compile it, run it,
 * throw it away. There is no endpoint that names an image, a mount, a capability, a network,
 * a user or a command. That is the security property the whole separation exists for — a
 * worker compromised by a malicious submission inherits <em>this</em>, not the Docker socket.
 *
 * <h2>The caller is not trusted</h2>
 * Even though the only caller is our own worker on a private network:
 * <ul>
 *   <li>Limits are {@linkplain ExecutionLimits#clampedTo clamped} to a configured ceiling,
 *       so a request for a twelve-hour run gets the maximum instead.</li>
 *   <li>Source size is capped before anything is created.</li>
 *   <li>Language is an enum; an unknown value fails to deserialise and never reaches code
 *       that picks an image.</li>
 *   <li>Workspace ids are opaque UUIDs held in this process; a caller cannot address a
 *       resource that was not handed to it.</li>
 * </ul>
 *
 * <h2>What it says when things go wrong</h2>
 * Errors carry a short, fixed phrase and no detail. A Docker error message can contain image
 * names, daemon paths and host configuration, and this service talks to a process that
 * handles untrusted input, so the daemon's own words are logged here and never returned.
 */
@RestController
@RequestMapping("/internal/executions")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

    private final SandboxService sandboxService;
    private final WorkspaceRegistry registry;
    private final SandboxMetrics metrics;
    private final SandboxPolicy policy;
    private final ExecutionLimits ceiling;
    private final int maxSourceBytes;

    public ExecutionController(SandboxService sandboxService,
                               WorkspaceRegistry registry,
                               SandboxMetrics metrics,
                               SandboxPolicy policy,
                               @Value("${codearena.sandbox.max-wall-clock-ms:60000}") long maxWallClockMs,
                               @Value("${codearena.sandbox.max-memory-mb:1024}") int maxMemoryMb,
                               @Value("${codearena.sandbox.max-cpus:2.0}") double maxCpus,
                               @Value("${codearena.sandbox.max-pids:128}") int maxPids,
                               @Value("${codearena.sandbox.max-output-bytes:1048576}") int maxOutputBytes,
                               @Value("${codearena.sandbox.max-source-bytes:262144}") int maxSourceBytes) {
        this.sandboxService = sandboxService;
        this.registry = registry;
        this.metrics = metrics;
        this.policy = policy;
        this.ceiling = new ExecutionLimits(maxWallClockMs, maxMemoryMb, maxCpus, maxPids, maxOutputBytes);
        this.maxSourceBytes = maxSourceBytes;
    }

    @PostMapping
    public ExecutionApi.PrepareResponse prepare(@RequestBody ExecutionApi.PrepareRequest request) {
        if (request.language() == null || request.source() == null) {
            throw new BadExecutionRequestException("language and source are required");
        }
        if (request.source().length() > maxSourceBytes) {
            throw new BadExecutionRequestException("source exceeds the maximum size");
        }
        // The submission id is a label for operators. It is never interpreted, never placed
        // on a command line, and never used to name a resource.
        String submissionId = request.submissionId() == null ? "unknown" : request.submissionId();

        SandboxService.Workspace workspace =
                sandboxService.prepare(submissionId, request.language(), request.source());
        String workspaceId = registry.register(workspace);
        log.info("event=WORKSPACE_PREPARED workspace={} language={} open={}",
                workspaceId, request.language(), registry.openCount());
        return new ExecutionApi.PrepareResponse(workspaceId);
    }

    @PostMapping("/{workspaceId}/compile")
    public ExecutionResult compile(@PathVariable String workspaceId,
                                   @RequestBody ExecutionApi.CompileRequest request) {
        metrics.executionStarted();
        return registry.get(workspaceId).compile(clamp(request.limits()));
    }

    @PostMapping("/{workspaceId}/run")
    public ExecutionResult run(@PathVariable String workspaceId,
                               @RequestBody ExecutionApi.RunRequest request) {
        metrics.executionStarted();
        return registry.get(workspaceId).run(request.stdin(), clamp(request.limits()));
    }

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<Void> release(@PathVariable String workspaceId) {
        // Idempotent by construction: releasing an unknown workspace is a no-op, so a
        // worker retrying a cleanup after a network blip cannot fail because it succeeded.
        registry.release(workspaceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * What this service is currently doing and how it is configured.
     *
     * <p>Deliberately free of anything that would help an attacker: no image names, no
     * paths, no daemon version, no host detail. Counts and configured ceilings only.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "openWorkspaces", registry.openCount(),
                "capacity", registry.capacity(),
                "seccompProfileApplied", policy.seccompProfileApplied(),
                "metrics", metrics.snapshot());
    }

    private ExecutionLimits clamp(ExecutionLimits requested) {
        if (requested == null) {
            throw new BadExecutionRequestException("limits are required");
        }
        return requested.clampedTo(ceiling);
    }

    // ------------------------------------------------------------------ error handling

    @ExceptionHandler(WorkspaceRegistry.UnknownWorkspaceException.class)
    public ResponseEntity<Map<String, String>> unknownWorkspace() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NO_SUCH_WORKSPACE"));
    }

    @ExceptionHandler(WorkspaceRegistry.CapacityExceededException.class)
    public ResponseEntity<Map<String, String>> atCapacity(WorkspaceRegistry.CapacityExceededException e) {
        // 503 with Retry-After is the honest answer: the request was fine, the machine is
        // full. The worker turns this into a system error rather than a verdict.
        log.warn("event=EXECUTOR_AT_CAPACITY open={}", registry.openCount());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of("error", "AT_CAPACITY"));
    }

    @ExceptionHandler(BadExecutionRequestException.class)
    public ResponseEntity<Map<String, String>> badRequest(BadExecutionRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }

    /**
     * Anything unforeseen.
     *
     * <p>The cause is logged in full and answered with a fixed phrase. Returning the
     * exception's message could hand the caller a Docker error containing image names or
     * daemon paths, and the caller is one step removed from untrusted code.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        metrics.dockerError();
        log.error("event=EXECUTION_FAILED reason={}", e.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "EXECUTION_FAILED"));
    }

    /** The request could not be honoured as written. */
    public static class BadExecutionRequestException extends RuntimeException {
        public BadExecutionRequestException(String message) {
            super(message);
        }
    }
}
