package com.codearena.worker.execution;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionApi;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionOutcome;
import com.codearena.shared.execution.ExecutionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the worker behaves when the execution service misbehaves.
 *
 * <h2>The property under test</h2>
 * <b>An executor failure must never look like a program failure.</b> A service that is down,
 * full, slow or returning nonsense has to produce {@code INFRASTRUCTURE_FAILURE}, which
 * {@code JudgeService} turns into SYSTEM_ERROR — never a RUNTIME_ERROR or a WRONG_ANSWER.
 * Getting this wrong tells a submitter their code is broken because our infrastructure was,
 * which is the single most damaging class of bug a judge can have.
 *
 * <p>A real HTTP server on a loopback port, rather than a mocked client: the failures worth
 * testing here are transport failures — a connection refused, a 503, a truncated body — and
 * a mock of the client cannot produce any of them faithfully.
 */
class RemoteExecutionServiceTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(1_000, 128, 1.0, 32, 4_096);

    private HttpServer server;
    private final List<String> presentedTokens = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private RemoteExecutionService serviceWithToken(String token) {
        return new RemoteExecutionService(baseUrl(), token, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private RemoteExecutionService service() {
        return serviceWithToken("a-token-that-is-at-least-32-characters-long");
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            presentedTokens.add(String.valueOf(exchange.getRequestHeaders().getFirst(ExecutionApi.TOKEN_HEADER)));
            send(exchange, status, body);
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // =============================================================== configuration

    /**
     * A worker with no token cannot judge anything, so it says so at boot rather than
     * turning every submission into a system error one at a time.
     */
    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> serviceWithToken(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTOR_TOKEN");

        assertThatThrownBy(() -> serviceWithToken(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendsTheTokenOnEveryRequest() {
        respond("/internal/executions", 200, "{\"workspaceId\":\"w1\"}");
        server.start();

        service().prepare("s1", Language.PYTHON, "print(1)");

        assertThat(presentedTokens).containsExactly("a-token-that-is-at-least-32-characters-long");
    }

    // ============================================================ preparation failures

    /**
     * At capacity is a real condition, not an error in the request, and the worker must
     * treat it as one: the submission stays unjudged rather than being failed.
     */
    @Test
    void treatsCapacityRefusalAsUnavailable() {
        respond("/internal/executions", 503, "{\"error\":\"AT_CAPACITY\"}");
        server.start();

        assertThatThrownBy(() -> service().prepare("s1", Language.PYTHON, "print(1)"))
                .isInstanceOf(ExecutionService.ExecutionUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void treatsAnUnreachableExecutorAsUnavailable() {
        // Never started: the port is closed, so the connection is refused outright.
        assertThatThrownBy(() -> service().prepare("s1", Language.PYTHON, "print(1)"))
                .isInstanceOf(ExecutionService.ExecutionUnavailableException.class)
                .hasMessageContaining("unreachable");
    }

    /** A 200 with no workspace is as useless as a 500, and must not be mistaken for success. */
    @Test
    void rejectsASuccessResponseThatCarriesNoWorkspace() {
        respond("/internal/executions", 200, "{}");
        server.start();

        assertThatThrownBy(() -> service().prepare("s1", Language.PYTHON, "print(1)"))
                .isInstanceOf(ExecutionService.ExecutionUnavailableException.class)
                .hasMessageContaining("no workspace");
    }

    // =========================================================== execution failures

    /**
     * The central mapping. Every one of these is a failure of ours, and each must produce an
     * infrastructure failure rather than anything that reads as a judgement on the code.
     */
    @Test
    void mapsEveryExecutorFailureToAnInfrastructureFailure() {
        record Case(String name, int status, String body) {
        }
        List<Case> cases = List.of(
                new Case("internal error", 500, "{\"error\":\"EXECUTION_FAILED\"}"),
                new Case("workspace gone", 404, "{\"error\":\"NO_SUCH_WORKSPACE\"}"),
                new Case("unauthorised", 401, ""),
                new Case("at capacity", 503, "{\"error\":\"AT_CAPACITY\"}"),
                new Case("empty body", 200, ""));

        for (Case testCase : cases) {
            List<ExecutionResult> results = runAgainst(exchange -> {
                try {
                    send(exchange, testCase.status(), testCase.body());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            for (ExecutionResult result : results) {
                assertThat(result.outcome())
                        .as("%s must be an infrastructure failure, not a verdict", testCase.name())
                        .isEqualTo(ExecutionOutcome.INFRASTRUCTURE_FAILURE);
                // Never a clean exit: a zero exit code here would read as "ran and succeeded".
                assertThat(result.succeeded()).isFalse();
            }
        }
    }

    /** A well-formed result passes through unchanged; the transport adds no interpretation. */
    @Test
    void passesAGenuineResultThrough() {
        respond("/internal/executions", 200, "{\"workspaceId\":\"w1\"}");
        respond("/internal/executions/w1/run", 200, """
                {"outcome":"COMPLETED","exitCode":0,"stdout":"42\\n","stderr":"",
                 "durationMs":12,"detail":null}
                """);
        server.start();

        ExecutionService.Workspace workspace = service().prepare("s1", Language.PYTHON, "print(42)");
        ExecutionResult result = workspace.run("", LIMITS);

        assertThat(result.outcome()).isEqualTo(ExecutionOutcome.COMPLETED);
        assertThat(result.stdout()).isEqualTo("42\n");
        assertThat(result.durationMs()).isEqualTo(12);
    }

    // =================================================================== cleanup

    /**
     * A failed release must not propagate. The executor closes abandoned workspaces on a
     * timer and its reaper removes the volumes, so a lost DELETE delays cleanup rather than
     * leaking — and a verdict that has already been decided must not be lost to a cleanup
     * error on the way out.
     */
    @Test
    void swallowsAFailureToReleaseTheWorkspace() {
        respond("/internal/executions", 200, "{\"workspaceId\":\"w1\"}");
        server.createContext("/internal/executions/w1", exchange -> send(exchange, 500, ""));
        server.start();

        ExecutionService.Workspace workspace = service().prepare("s1", Language.PYTHON, "print(1)");

        // Closing twice is also safe: the second call must do nothing at all.
        workspace.close();
        workspace.close();
    }

    /** Runs compile and run against a handler that fails, returning both results. */
    private List<ExecutionResult> runAgainst(Consumer<HttpExchange> handler) {
        HttpServer local;
        try {
            local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        local.createContext("/internal/executions", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/internal/executions")) {
                send(exchange, 200, "{\"workspaceId\":\"w1\"}");
            } else {
                handler.accept(exchange);
            }
        });
        local.start();
        try {
            RemoteExecutionService service = new RemoteExecutionService(
                    "http://127.0.0.1:" + local.getAddress().getPort(),
                    "a-token-that-is-at-least-32-characters-long",
                    Duration.ofSeconds(2), Duration.ofSeconds(5));
            ExecutionService.Workspace workspace = service.prepare("s1", Language.PYTHON, "print(1)");

            List<ExecutionResult> results = new ArrayList<>();
            results.add(workspace.compile(LIMITS));
            results.add(workspace.run("", LIMITS));
            return results;
        } finally {
            local.stop(0);
        }
    }
}
