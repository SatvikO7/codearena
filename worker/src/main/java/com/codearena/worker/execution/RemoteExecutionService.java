package com.codearena.worker.execution;

import com.codearena.shared.Language;
import com.codearena.shared.execution.ExecutionApi;
import com.codearena.shared.execution.ExecutionLimits;
import com.codearena.shared.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Runs code by asking the execution service to, rather than by driving Docker.
 *
 * <h2>Why the worker no longer touches Docker</h2>
 * Until Phase 6 this process held the Docker socket. That made the component which parses
 * untrusted program output, connects to PostgreSQL and Redis, and carries the judging logic
 * also the component that could start a privileged container mounting the host filesystem —
 * so any remote-code-execution bug anywhere in the worker was a host compromise.
 *
 * <p>Now it holds a bearer token for a service that will do four things on its behalf. A
 * worker taken over completely can ask for a program to be compiled and run inside a
 * sandbox, within limits the executor clamps. It cannot ask for a mount, an image, a
 * capability or a network, because the contract has nowhere to put them.
 *
 * <h2>A failure here is never a verdict</h2>
 * An executor that is down, full or unreachable must never look like a program that crashed.
 * Blaming a submitter for our own outage is the specific mistake this mapping exists to
 * prevent, and there are two cases rather than one:
 *
 * <ul>
 *   <li><b>Preparation fails</b> — no sandbox was obtained, so nothing has been judged.
 *       {@link ExecutionUnavailableException} propagates, the submission is left claimed but
 *       unfinished, and the recovery sweeper judges it again once the lease expires. A
 *       machine that is briefly full must not permanently fail somebody's code.</li>
 *   <li><b>An execution fails midway</b> — the sandbox existed and something went wrong with
 *       it. That becomes {@code INFRASTRUCTURE_FAILURE}, which {@code JudgeService} turns
 *       into SYSTEM_ERROR.</li>
 * </ul>
 */
@Component
public class RemoteExecutionService implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RemoteExecutionService.class);

    private final RestClient client;

    public RemoteExecutionService(
            @Value("${codearena.executor.url:http://executor:8082}") String baseUrl,
            @Value("${codearena.executor.token:}") String token,
            @Value("${codearena.executor.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${codearena.executor.read-timeout:PT2M}") Duration readTimeout) {

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "codearena.executor.token (EXECUTOR_TOKEN) must be set; it must match the "
                    + "execution service's token or no submission can be judged.");
        }

        // The read timeout has to outlast the longest execution the executor will allow,
        // or a legitimately slow compile would be reported as an infrastructure failure.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(ExecutionApi.TOKEN_HEADER, token)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Workspace prepare(String submissionId, Language language, String source) {
        ExecutionApi.PrepareResponse response;
        try {
            response = client.post()
                    .uri("/internal/executions")
                    .body(new ExecutionApi.PrepareRequest(submissionId, language, source))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, failure) -> {
                        // Raised as our own exception so the status code, and nothing from
                        // the response body, decides what the worker reports.
                        throw new ExecutionUnavailableException(
                                "executor returned " + failure.getStatusCode());
                    })
                    .body(ExecutionApi.PrepareResponse.class);
        } catch (RestClientException e) {
            throw new ExecutionUnavailableException("executor unreachable: " + e.getClass().getSimpleName());
        }

        if (response == null || response.workspaceId() == null) {
            throw new ExecutionUnavailableException("executor returned no workspace");
        }
        return new RemoteWorkspace(submissionId, response.workspaceId());
    }

    private final class RemoteWorkspace implements Workspace {

        private final String submissionId;
        private final String workspaceId;
        private boolean closed;

        private RemoteWorkspace(String submissionId, String workspaceId) {
            this.submissionId = submissionId;
            this.workspaceId = workspaceId;
        }

        @Override
        public ExecutionResult compile(ExecutionLimits limits) {
            return call("/internal/executions/" + workspaceId + "/compile",
                    new ExecutionApi.CompileRequest(limits));
        }

        @Override
        public ExecutionResult run(String stdin, ExecutionLimits limits) {
            return call("/internal/executions/" + workspaceId + "/run",
                    new ExecutionApi.RunRequest(stdin, limits));
        }

        private ExecutionResult call(String uri, Object body) {
            try {
                ExecutionResult result = client.post()
                        .uri(uri)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, failure) -> {
                            throw new ExecutionUnavailableException(
                                    "executor returned " + failure.getStatusCode());
                        })
                        .body(ExecutionResult.class);
                return result == null
                        ? ExecutionResult.infrastructureFailure("executor returned an empty result")
                        : result;
            } catch (ExecutionUnavailableException e) {
                return ExecutionResult.infrastructureFailure(e.getMessage());
            } catch (RestClientException e) {
                return ExecutionResult.infrastructureFailure(
                        "executor unreachable: " + e.getClass().getSimpleName());
            }
        }

        /**
         * Releases the remote workspace.
         *
         * <p>Best-effort on purpose. If this call is lost the executor closes the workspace
         * itself once it goes idle, and its reaper removes the volume after that — so a
         * failure here delays cleanup rather than leaking, and must never mask a verdict.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                client.delete()
                        .uri("/internal/executions/" + workspaceId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.warn("event=WORKSPACE_RELEASE_FAILED submission={} workspace={} reason={}",
                        submissionId, workspaceId, e.getClass().getSimpleName());
            }
        }
    }

}
