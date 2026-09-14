package com.codearena.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rate-limited caller is told, and what they must not be able to work out from it.
 */
class RateLimitResponseTest {

    private static Map<String, String> headersFor(RateLimitDecision decision) {
        Map<String, String> headers = new LinkedHashMap<>();
        RateLimitHeaders.apply(headers::put, decision);
        return headers;
    }

    /**
     * {@code Retry-After} on a response that was allowed would be meaningless, and clients
     * that honour it would sleep for no reason.
     */
    @Test
    void advertisesTheAllowanceOnASuccessButNeverRetryAfter() {
        Map<String, String> headers = headersFor(new RateLimitDecision(true, 10, 7, 0, 0));

        assertThat(headers)
                .containsEntry(RateLimitHeaders.LIMIT, "10")
                .containsEntry(RateLimitHeaders.REMAINING, "7")
                .containsEntry(RateLimitHeaders.RESET, "0")
                .doesNotContainKey("Retry-After");
    }

    @Test
    void tellsARejectedCallerExactlyHowLongToWait() {
        Map<String, String> headers = headersFor(new RateLimitDecision(false, 10, 0, 17, 17));

        assertThat(headers)
                .containsEntry(RateLimitHeaders.REMAINING, "0")
                .containsEntry("Retry-After", "17");
    }

    /**
     * The one message, for every policy. A throttled login must read exactly like a
     * throttled registration or a throttled search: login is deliberately built not to
     * reveal whether an account exists, and a limiter that answered differently for real
     * and imaginary accounts would give that back.
     */
    @Test
    void saysNothingAboutWhichControlRefusedTheRequest() {
        assertThat(RateLimitHeaders.MESSAGE)
                .doesNotContainIgnoringCase("login")
                .doesNotContainIgnoringCase("account")
                .doesNotContainIgnoringCase("submission")
                .doesNotContainIgnoringCase("redis")
                .doesNotContainIgnoringCase("bucket")
                .doesNotContainIgnoringCase("token");
        assertThat(RateLimitHeaders.ERROR_CODE).isEqualTo("RATE_LIMITED");
    }

    /**
     * A sub-second wait must never round down to zero: a client honouring
     * {@code Retry-After: 0} would retry immediately, be refused again, and spin — the
     * header would have caused the hammering it exists to prevent.
     */
    @Test
    void roundsAPartialSecondUpSoAClientNeverSpins() {
        assertThat(RateLimitService.ceilSeconds(1)).isEqualTo(1);
        assertThat(RateLimitService.ceilSeconds(999)).isEqualTo(1);
        assertThat(RateLimitService.ceilSeconds(1000)).isEqualTo(1);
        assertThat(RateLimitService.ceilSeconds(1001)).isEqualTo(2);
        assertThat(RateLimitService.ceilSeconds(0)).isEqualTo(0);
        assertThat(RateLimitService.ceilSeconds(-5)).isEqualTo(0);
    }

    /**
     * The key carries the policy and the kind of identity. Without the policy segment a
     * user's submissions and their standings reads would draw on one allowance; without the
     * kind, two categories of identity could name the same bucket.
     */
    @Test
    void buildsOneKeyPerPolicyAndIdentity() {
        CallerIdentity alice = CallerIdentity.ofUser("alice-public-id");

        assertThat(RateLimitService.keyFor(RateLimitPolicy.SUBMISSION, alice))
                .isEqualTo("codearena:rl:submission:u:alice-public-id")
                .isNotEqualTo(RateLimitService.keyFor(RateLimitPolicy.STANDINGS, alice));
    }

    /** An unreachable backend on a closed policy still gives the caller a real answer. */
    @Test
    void reportsAUsableWaitWhenTheBackendIsUnreachable() {
        RateLimitDecision decision = RateLimitDecision.unavailable(10, 4);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterSeconds()).isEqualTo(4);
    }
}
