package com.codearena.ratelimit;

/**
 * The limiter's answer: allow or reject, plus exactly enough to tell the caller what
 * happened.
 *
 * <p>Callers never see a token count, a bucket, a key or a Redis anything. That is the
 * point of the type: a controller asks whether it may proceed and, if not, how long to
 * wait. Were this to expose the mechanism, the mechanism would end up in the controllers.
 *
 * <h2>Why these numbers can be stated rather than estimated</h2>
 * A token bucket regenerates continuously at a known rate, so the wait until the next
 * request would succeed is arithmetic. That is what makes {@code Retry-After} honest here.
 * A sliding-window log or a probabilistic counter could not say it without guessing, and a
 * guessed {@code Retry-After} trains clients to ignore the header.
 *
 * @param allowed           whether the request may proceed
 * @param limit             the bucket's capacity, for {@code RateLimit-Limit}
 * @param remaining         whole tokens left after this decision. Floored: a fractional
 *                          token cannot serve a request, so reporting it would promise
 *                          something that is about to be refused
 * @param retryAfterSeconds seconds until this request would be admitted. Zero when allowed
 * @param resetSeconds      seconds until at least one token is available. Zero while the
 *                          caller still has one. Deliberately *not* "seconds until the
 *                          bucket is full", which is a longer and less useful number
 */
public record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        long retryAfterSeconds,
        long resetSeconds) {

    /** Used when limiting is switched off, and when an open policy cannot reach Redis. */
    static RateLimitDecision unlimited(int limit) {
        return new RateLimitDecision(true, limit, limit, 0, 0);
    }

    /**
     * Used when a closed policy cannot reach Redis.
     *
     * <p>Reports no remaining allowance and asks the caller back after one refill interval.
     * That is a real answer rather than a fabricated one: the limiter genuinely does not
     * know the state, and the interval is the soonest a token could have appeared.
     */
    static RateLimitDecision unavailable(int limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, 0, retryAfterSeconds, retryAfterSeconds);
    }
}
