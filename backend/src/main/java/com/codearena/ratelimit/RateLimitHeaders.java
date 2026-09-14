package com.codearena.ratelimit;

import org.springframework.http.HttpHeaders;

import java.util.function.BiConsumer;

/**
 * The headers a rate-limited response carries, and the one message it is allowed to say.
 *
 * <h2>Only what the algorithm can actually guarantee</h2>
 * A token bucket regenerates at a fixed rate, so "how long until this would be admitted"
 * and "how long until one token exists" are both exact. Those are the two numbers reported.
 * {@code RateLimit-Reset} is the second of them — the wait for the next token — and not the
 * wait for a full bucket, which is a different and much longer number. Publishing a value
 * whose meaning is fuzzy would be worse than publishing none: clients would schedule
 * against it and be refused anyway.
 *
 * <h2>What the response must not say</h2>
 * Not the policy, not the bucket, not the key, not the identity, not the remaining allowance
 * of any other caller, and nothing about Redis. The body is one fixed sentence for every
 * rate-limited request on every endpoint. In particular a throttled login says exactly what
 * a throttled registration says, so a 429 can never be read as confirmation that an account
 * exists — login is carefully built not to be an enumeration oracle, and a limiter that
 * answered differently for real and imaginary accounts would quietly undo that.
 */
public final class RateLimitHeaders {

    /** The bucket's capacity. */
    public static final String LIMIT = "RateLimit-Limit";

    /** Whole requests still available to this caller under this policy. */
    public static final String REMAINING = "RateLimit-Remaining";

    /** Seconds until at least one request is available again. */
    public static final String RESET = "RateLimit-Reset";

    /** The machine-readable code in the error envelope. */
    public static final String ERROR_CODE = "RATE_LIMITED";

    /** Identical for every policy, for the reasons above. */
    public static final String MESSAGE =
            "Too many requests. Please wait a moment and try again.";

    private RateLimitHeaders() {
    }

    /**
     * Writes the advisory headers.
     *
     * <p>Set on successful responses too, not only on rejections, so a well-behaved client
     * can slow down before it is refused rather than after. {@code Retry-After} is the
     * exception — it appears only on a 429, where it means something.
     *
     * @param sink accepts a header name and value; a servlet response and a
     *             {@link HttpHeaders} are both spelled differently and neither should leak
     *             into the callers of this class
     */
    public static void apply(BiConsumer<String, String> sink, RateLimitDecision decision) {
        sink.accept(LIMIT, Integer.toString(decision.limit()));
        sink.accept(REMAINING, Integer.toString(decision.remaining()));
        sink.accept(RESET, Long.toString(decision.resetSeconds()));
        if (!decision.allowed()) {
            sink.accept(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        }
    }
}
