package com.codearena.ratelimit;

import com.codearena.common.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Applies {@link RateLimited} to the handler a request has resolved to.
 *
 * <h2>Where this sits, and why it matters</h2>
 * A Spring MVC interceptor runs <em>after</em> the whole security filter chain and after
 * handler mapping. That ordering is deliberate and is the answer to "is rate limiting
 * replacing authorisation":
 *
 * <pre>
 *   CORS → CSRF → authentication → authorisation → <b>rate limit</b> → validation → handler
 * </pre>
 *
 * <ul>
 *   <li>An unauthenticated caller gets <b>401</b>, not 429. They never reach the limiter, so
 *       they cannot spend somebody else's allowance, and the limiter never has to guess at
 *       an identity for them.</li>
 *   <li>A caller without the role gets <b>403</b>, not 429. Authorisation is unchanged and
 *       still decides first.</li>
 *   <li>A caller who is authenticated and permitted, but over their allowance, gets
 *       <b>429</b>.</li>
 * </ul>
 *
 * <p>Running the limiter earlier — in a filter ahead of security — would invert the first
 * two: an anonymous request would be counted against a shared bucket and could be answered
 * 429 instead of 401, which both leaks that the endpoint is being probed successfully and
 * lets anonymous traffic exhaust the allowance of endpoints it was never allowed to call.
 *
 * <p>The one thing that happens after the limiter is request-body validation. That is the
 * right way round too: a limit that could only be reached by sending a <em>valid</em> body
 * would be trivially bypassed by sending invalid ones.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitService rateLimitService;
    private final CallerIdentityResolver identityResolver;
    private final RateLimitViolationAuditor violationAuditor;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                CallerIdentityResolver identityResolver,
                                RateLimitViolationAuditor violationAuditor,
                                ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.identityResolver = identityResolver;
        this.violationAuditor = violationAuditor;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        RateLimited annotation = annotationOn(handler);
        if (annotation == null || !applies(annotation, request)) {
            return true;
        }

        RateLimitPolicy policy = annotation.value();
        CallerIdentity identity = identityFor(policy, request);
        RateLimitDecision decision = rateLimitService.check(policy, identity);

        RateLimitHeaders.apply(response::setHeader, decision);
        if (decision.allowed()) {
            return true;
        }

        reject(request, response, policy, identity, decision);
        return false;
    }

    private static RateLimited annotationOn(Object handler) {
        // Anything that is not a controller method -- a static resource, the error
        // dispatch -- carries no policy and is left alone.
        return handler instanceof HandlerMethod method
                ? method.getMethodAnnotation(RateLimited.class)
                : null;
    }

    /** Honours {@link RateLimited#onlyWhenParameterPresent()}. */
    private static boolean applies(RateLimited annotation, HttpServletRequest request) {
        String parameter = annotation.onlyWhenParameterPresent();
        if (parameter.isEmpty()) {
            return true;
        }
        String value = request.getParameter(parameter);
        return value != null && !value.isBlank();
    }

    /**
     * Whose allowance to spend.
     *
     * <p>A {@code CLIENT}-scoped policy is always keyed on the caller even if a session
     * happens to exist, because those policies are about anonymous traffic — registering an
     * account while already signed in is still one client creating accounts.
     *
     * <p>{@code ACCOUNT} scope never reaches here: it is keyed on a value inside the
     * request body, which an interceptor cannot read without consuming the stream. The
     * login controller applies that one directly.
     */
    private CallerIdentity identityFor(RateLimitPolicy policy, HttpServletRequest request) {
        return switch (policy.scope()) {
            case USER -> identityResolver.resolve(request);
            case CLIENT -> identityResolver.resolveClient(request);
            case ACCOUNT -> throw new IllegalStateException(
                    "account-scoped policy " + policy.id() + " cannot be applied by the interceptor");
        };
    }

    /**
     * Answers 429 in the project's standard error envelope.
     *
     * <p>Written here rather than thrown, because an interceptor returning false stops the
     * dispatch before any handler or {@code @ControllerAdvice} runs. The shape matches what
     * {@code GlobalExceptionHandler} produces for the login throttle, so the two enforcement
     * paths are indistinguishable from outside.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response,
                        RateLimitPolicy policy, CallerIdentity identity,
                        RateLimitDecision decision) throws IOException {

        boolean firstOfBurst = violationAuditor.noteRejection(policy, identity);
        if (firstOfBurst) {
            // One line per identity per cooldown. The identity kind, never the identity:
            // a log line is not the place to write down somebody's address, and an
            // attacker chooses how many of these they cause.
            log.warn("event=RATE_LIMIT_REJECTED policy={} identity={} method={} path={}",
                    policy.id(), identity.kind(), request.getMethod(), request.getRequestURI());
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                RateLimitHeaders.ERROR_CODE,
                RateLimitHeaders.MESSAGE,
                request.getRequestURI()));
    }
}
