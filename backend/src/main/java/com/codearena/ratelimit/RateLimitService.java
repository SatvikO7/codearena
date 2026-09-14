package com.codearena.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place that decides whether a request may proceed.
 *
 * <p>Everything above this — controllers, the interceptor — asks a question and reads
 * {@link RateLimitDecision#allowed()}. Nothing above this knows that the answer involves
 * Redis, a token bucket, a Lua script or a TTL. That separation is what keeps the algorithm
 * replaceable: swapping the bucket for a sliding window would touch this class and the
 * script, and nothing else.
 *
 * <h2>Distributed, and actually distributed</h2>
 * State lives in Redis, which every API instance shares, so the limit is the limit however
 * many instances are running. There is deliberately no in-memory cache or local fallback
 * counter in front of it: a local counter would make the enforced limit silently
 * proportional to the number of instances, while the documentation continued to claim one
 * number. A control that is wrong in an unstated direction is worse than one that is
 * absent, because it is believed.
 *
 * <h2>Atomic by construction</h2>
 * The whole decision runs as one Lua script inside Redis. A read-then-write from Java —
 * {@code GET} the count, compare, {@code INCR} — admits every concurrent caller who reads
 * the same value, which is a bypass that appears precisely under load.
 *
 * @see RateLimitPolicy
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** Namespaced like every other key this system puts in Redis. */
    static final String KEY_PREFIX = "codearena:rl:";

    /** Metric name; tags are policy, outcome and identity kind, all closed sets. */
    static final String DECISION_METRIC = "codearena.ratelimit.decisions";

    /** One ordinary request costs one token. */
    private static final int COST = 1;

    /** Redis being unreachable is worth logging, but not once per rejected request. */
    private static final long OUTAGE_LOG_INTERVAL_MS = 10_000;

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final RedisScript<List> tokenBucketScript;
    private final AtomicLong lastOutageLogAt = new AtomicLong(0);

    public RateLimitService(StringRedisTemplate redis,
                            RateLimitProperties properties,
                            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        script.setResultType(List.class);
        this.tokenBucketScript = script;
    }

    /**
     * Whether this identity may make one more request under this policy.
     *
     * <p>Consumes a token when it says yes, so it must be called exactly once per request.
     * It is not a query.
     */
    public RateLimitDecision check(RateLimitPolicy policy, CallerIdentity identity) {
        RateLimitProperties.Bucket bucket = properties.bucketFor(policy);

        if (!properties.enabled()) {
            return RateLimitDecision.unlimited(bucket.capacity());
        }

        try {
            List<?> result = redis.execute(tokenBucketScript,
                    List.of(keyFor(policy, identity)),
                    Integer.toString(bucket.capacity()),
                    Long.toString(bucket.refillIntervalMillis()),
                    Integer.toString(COST));

            RateLimitDecision decision = interpret(result, bucket);
            record(policy, identity, decision.allowed() ? "allowed" : "rejected");
            return decision;

        } catch (RuntimeException e) {
            return unavailable(policy, identity, bucket, e);
        }
    }

    /**
     * Restores an identity's full allowance under one policy.
     *
     * <p>Deleting the bucket is the refund: an absent bucket is a full one, which is the
     * same state the script would have reached by waiting. Used when an attempt turns out
     * to have been legitimate — a correct password clears the failed-login record for that
     * account — so that honest use never accumulates against itself.
     *
     * <p>A failure here is swallowed. The consequence of a missed refund is that a
     * legitimate caller keeps an allowance they had already spent, which the bucket
     * refills out of on its own; the consequence of throwing would be turning a successful
     * login into a 500.
     */
    public void reset(RateLimitPolicy policy, CallerIdentity identity) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.delete(keyFor(policy, identity));
        } catch (RuntimeException e) {
            logOutage("rate-limit reset failed", policy, e);
        }
    }

    /**
     * {@code codearena:rl:{policy}:{kind}:{identity}}.
     *
     * <p>The policy and the kind are both in the key, so no two policies and no two
     * categories of identity can share a bucket. Without the policy segment, a user's
     * submissions and their standings reads would draw on the same allowance; without the
     * kind, a user id and an address hash could in principle collide.
     */
    static String keyFor(RateLimitPolicy policy, CallerIdentity identity) {
        return KEY_PREFIX + policy.id() + ":" + identity.keySegment();
    }

    /** Converts the script's four numbers into the decision the rest of the system sees. */
    private static RateLimitDecision interpret(List<?> result, RateLimitProperties.Bucket bucket) {
        if (result == null || result.size() < 4) {
            // The script returns four values on every path; anything else means the script
            // and this class have drifted apart, which is a bug rather than a rejection.
            throw new IllegalStateException("the rate-limit script returned an unexpected result");
        }
        boolean allowed = number(result.get(0)) == 1;
        int remaining = (int) number(result.get(1));
        long retryAfter = ceilSeconds(number(result.get(2)));
        long reset = ceilSeconds(number(result.get(3)));
        return new RateLimitDecision(allowed, bucket.capacity(), Math.max(remaining, 0), retryAfter, reset);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Milliseconds to whole seconds, rounded up, and never zero for a real wait.
     *
     * <p>{@code Retry-After} is denominated in seconds, so a 300ms wait rounded down to 0
     * would invite an immediate retry that is certain to be refused — the client would
     * spin, and the header would have caused the problem it exists to prevent.
     */
    static long ceilSeconds(long milliseconds) {
        if (milliseconds <= 0) {
            return 0;
        }
        return (milliseconds + 999) / 1000;
    }

    /**
     * What to do when Redis cannot answer.
     *
     * <p>Per policy, and stated in the policy itself rather than decided here. The reasoning
     * behind each choice is in ADR-039; the short version is that Redis already holds every
     * session, so for authenticated traffic "Redis is down" and "the API is down" are close
     * to the same sentence, and failing a security control closed costs little that was
     * still working.
     */
    private RateLimitDecision unavailable(RateLimitPolicy policy, CallerIdentity identity,
                                          RateLimitProperties.Bucket bucket, RuntimeException cause) {
        logOutage("rate-limit backend unavailable", policy, cause);

        if (policy.failureMode() == RateLimitPolicy.FailureMode.OPEN) {
            record(policy, identity, "failed-open");
            return RateLimitDecision.unlimited(bucket.capacity());
        }
        record(policy, identity, "failed-closed");
        return RateLimitDecision.unavailable(bucket.capacity(),
                Math.max(1, Duration.ofMillis(bucket.refillIntervalMillis()).toSeconds()));
    }

    /**
     * One counter, three closed dimensions.
     *
     * <p>Policy, outcome and identity <em>kind</em> — never the identity itself. A metric
     * tagged with a username or an address creates one time series per caller, which turns
     * an attack into a monitoring outage and puts personal data in a store that was never
     * designed to hold it.
     */
    private void record(RateLimitPolicy policy, CallerIdentity identity, String outcome) {
        meterRegistry.counter(DECISION_METRIC,
                "policy", policy.id(),
                "outcome", outcome,
                "identity", identity.kind().name().toLowerCase(java.util.Locale.ROOT)).increment();
    }

    /** Throttled, so an outage produces a readable log rather than a flood of identical lines. */
    private void logOutage(String message, RateLimitPolicy policy, RuntimeException cause) {
        long now = System.currentTimeMillis();
        long last = lastOutageLogAt.get();
        if (now - last >= OUTAGE_LOG_INTERVAL_MS && lastOutageLogAt.compareAndSet(last, now)) {
            log.error("event=RATE_LIMIT_BACKEND_UNAVAILABLE policy={} mode={} detail={}",
                    policy.id(), policy.failureMode(), message, cause);
        }
    }
}
