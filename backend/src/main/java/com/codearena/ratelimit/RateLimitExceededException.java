package com.codearena.ratelimit;

/**
 * Thrown when a limit checked inside a service or controller — rather than by the
 * interceptor — refuses a request.
 *
 * <p>There is exactly one such limit: the per-account login throttle, which cannot run in
 * the interceptor because it is keyed on the identifier inside the request body. Everything
 * else is applied by {@link RateLimitInterceptor} and never reaches an exception.
 *
 * <p>Carries the decision so that {@code GlobalExceptionHandler} can answer with the same
 * 429 and the same headers the interceptor would have produced. A client must not be able
 * to tell which of the two paths refused it — a difference in the response shape would
 * reveal that the request got as far as naming an account.
 */
public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitPolicy policy;
    private final transient RateLimitDecision decision;

    public RateLimitExceededException(RateLimitPolicy policy, RateLimitDecision decision) {
        // The message is for the log, never for the response body: naming the policy in an
        // API response would tell an attacker which control they had tripped.
        super("rate limit exceeded for policy " + policy.id());
        this.policy = policy;
        this.decision = decision;
    }

    public RateLimitPolicy policy() {
        return policy;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
