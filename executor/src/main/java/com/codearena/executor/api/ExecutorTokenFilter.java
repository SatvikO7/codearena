package com.codearena.executor.api;

import com.codearena.shared.execution.ExecutionApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The only authentication this service has, and the only kind it needs.
 *
 * <p>One shared secret in a header, checked before Spring routes anything. The execution
 * service has exactly one caller — the judge worker, on a private Docker network — so there
 * are no users, no sessions and no roles to model. Adding Spring Security here would mean a
 * filter chain, a user store and an authorisation model in the one process that holds Docker
 * control, in exchange for nothing this deployment can express.
 *
 * <p>The token is <b>required</b>. There is no default and no "disabled in development"
 * switch: {@link #ExecutorTokenFilter} refuses to construct without one, so the service
 * cannot start unauthenticated by accident. A misconfiguration is a failure to boot, which
 * somebody notices, rather than an open door, which nobody does.
 *
 * <p>Comparison is constant-time. The window is small — an attacker already needs to be on
 * the internal network — but a timing-safe comparison costs one method call, and "small
 * window" is how side channels are argued away right up until they are not.
 *
 * <p>Actuator health is left open so Docker's health check can reach it without holding a
 * credential. It reports liveness and nothing about submissions.
 */
@Component
public class ExecutorTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ExecutorTokenFilter.class);

    private final byte[] expected;

    public ExecutorTokenFilter(@Value("${codearena.executor.token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "codearena.executor.token (EXECUTOR_TOKEN) must be set. The execution "
                    + "service holds Docker control and will not start without authentication.");
        }
        if (token.length() < 32) {
            throw new IllegalStateException(
                    "codearena.executor.token must be at least 32 characters.");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(ExecutionApi.TOKEN_HEADER);
        if (presented == null || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            // No detail, no hint about which part was wrong, and nothing echoed back.
            log.warn("event=EXECUTOR_AUTH_REJECTED path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
