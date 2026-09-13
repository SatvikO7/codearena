package com.codearena.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request an identifier, so a log line, an audit event and a database write can
 * be tied back to the same call.
 *
 * <p>The chain this makes traceable:
 *
 * <pre>
 *   request  →  application log  →  audit event
 *      └────────── one id ──────────────┘
 * </pre>
 *
 * <p>Without it, "the audit says a contest was cancelled at 14:02" and "the log shows an
 * error at 14:02" are two facts that have to be correlated by eye. With it they are one
 * query.
 *
 * <h2>Why the client's id is not simply trusted</h2>
 * A caller-supplied {@code X-Request-Id} is genuinely useful — it lets a frontend correlate
 * its own logs with the server's — but it is attacker-controlled text that ends up in log
 * files and in a table that is never deleted. So it is accepted and then
 * {@linkplain #sanitise sanitised}: a bounded length, and only characters that cannot forge
 * a log line. Anything else is replaced with a generated id rather than rejected, because
 * failing a request over a cosmetic header would be a poor trade.
 *
 * <p>The id is not a security control and is not treated as one. It identifies a request; it
 * authorises nothing.
 *
 * <h2>Ordering</h2>
 * Highest precedence, so the id exists before authentication runs — the requests most worth
 * tracing are the ones that are about to be rejected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Accepted from the caller, and echoed back so a client can correlate its own logs. */
    public static final String HEADER = "X-Request-Id";

    /** The MDC key, which the log pattern prints. */
    public static final String MDC_KEY = "requestId";

    /** Long enough for a UUID or a trace id; short enough to bound a log line and a column. */
    static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        RequestContext.setRequestId(requestId);
        response.setHeader(HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Both are thread-local and this thread goes back to a pool. Leaving either set
            // would stamp the next, unrelated request with this one's id.
            RequestContext.clear();
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts a caller's id only if it is safe to write down.
     *
     * <p>Letters, digits, hyphens and underscores only. That excludes newlines — which would
     * let a caller forge extra log lines — and quotes, braces and control characters, which
     * would break the structured formats this value lands in.
     */
    static String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            boolean allowed = Character.isLetterOrDigit(character) || character == '-' || character == '_';
            if (!allowed) {
                return UUID.randomUUID().toString();
            }
        }
        return candidate;
    }
}
