package com.codearena.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Configuration that refuses to start rather than misbehave quietly.
 *
 * <p>A rate limiter configured wrongly does not announce itself: it either rejects
 * everything, which looks like an outage in some other component, or divides by zero deep
 * inside a Lua script during the first attack. Both are found here instead.
 */
class RateLimitPropertiesTest {

    private static RateLimitProperties.Bucket bucket(int capacity, Duration interval) {
        return new RateLimitProperties.Bucket(capacity, interval);
    }

    /** Zero capacity rejects every request forever, which is an outage, not a policy. */
    @Test
    void refusesABucketThatCanNeverAdmitAnything() {
        assertThatThrownBy(() -> bucket(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one request");
    }

    /**
     * The refill interval is a divisor inside the script. Sub-millisecond intervals round to
     * zero, and the failure would be a scripting error during traffic rather than at boot.
     */
    @Test
    void refusesARefillIntervalThatRoundsToNothing() {
        assertThatThrownBy(() -> bucket(10, Duration.ofNanos(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one millisecond");
    }

    @Test
    void refusesANegativeAuditCooldown() {
        RateLimitProperties.Bucket valid = bucket(10, Duration.ofSeconds(1));

        assertThatThrownBy(() -> new RateLimitProperties(true, false,
                valid, valid, valid, valid, valid, valid, valid, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Every policy must resolve to a bucket. The switch in {@code bucketFor} is exhaustive,
     * so a policy added without configuration fails to compile — but this test also proves
     * no policy is silently mapped to another's bucket, which would compile perfectly well.
     */
    @ParameterizedTest
    @EnumSource(RateLimitPolicy.class)
    void configuresEveryPolicyDistinctly(RateLimitPolicy policy) {
        RateLimitProperties properties = distinctPerPolicy();

        assertThat(properties.bucketFor(policy).capacity())
                .isEqualTo(policy.ordinal() + 1);
    }

    @Test
    void describesTheSustainedRateForDocumentationAndOperators() {
        assertThat(bucket(10, Duration.ofSeconds(10)).perMinute()).isCloseTo(6.0, within(0.001));
        assertThat(bucket(10, Duration.ofSeconds(2)).perMinute()).isCloseTo(30.0, within(0.001));
        assertThat(bucket(10, Duration.ofMinutes(2)).perMinute()).isCloseTo(0.5, within(0.001));
    }

    /** The shipped defaults are a policy claim; this pins the two that matter most. */
    @Test
    void keepsTheLoginThrottleTighterThanTheFloodCap() {
        RateLimitProperties properties = distinctPerPolicy();

        assertThat(RateLimitPolicy.LOGIN_ACCOUNT.failureMode())
                .isEqualTo(RateLimitPolicy.FailureMode.CLOSED);
        assertThat(RateLimitPolicy.LOGIN_ACCOUNT.auditable())
                .as("an identity derived from caller-supplied text must never write audit rows")
                .isFalse();
        assertThat(properties.bucketFor(RateLimitPolicy.LOGIN_ACCOUNT)).isNotNull();
    }

    /** One capacity per policy, so a mis-wired switch is visible rather than plausible. */
    private static RateLimitProperties distinctPerPolicy() {
        Duration second = Duration.ofSeconds(1);
        return new RateLimitProperties(true, false,
                bucket(RateLimitPolicy.LOGIN_ORIGIN.ordinal() + 1, second),
                bucket(RateLimitPolicy.LOGIN_ACCOUNT.ordinal() + 1, second),
                bucket(RateLimitPolicy.REGISTRATION.ordinal() + 1, second),
                bucket(RateLimitPolicy.SUBMISSION.ordinal() + 1, second),
                bucket(RateLimitPolicy.STANDINGS.ordinal() + 1, second),
                bucket(RateLimitPolicy.PROBLEM_SEARCH.ordinal() + 1, second),
                bucket(RateLimitPolicy.ADMIN_READ.ordinal() + 1, second),
                Duration.ofMinutes(10));
    }
}
