package com.codearena.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Every rate-limit number the system uses, in one place and read from configuration.
 *
 * <p>No policy value is written into a controller or a service. An operator who needs to
 * loosen submissions during a contest, or tighten logins under an attack, changes an
 * environment variable and restarts — they do not edit Java and rebuild. Equally, none of
 * it is reachable from a request: these are server-side properties, and no endpoint reads a
 * limit, a capacity or a window from the client.
 *
 * <h2>Validated at startup, not at the first rejection</h2>
 * A bucket with a capacity of zero rejects everything and a refill interval of zero divides
 * by zero inside the Lua script. Both are configuration mistakes, and both are found here
 * when the context refuses to start, rather than in production the first time somebody
 * tries to log in.
 *
 * @param enabled              master switch. Off leaves every request unlimited; it exists
 *                             for local development and for tests that are about something
 *                             else, and it is on by default so that forgetting it is not a
 *                             way to ship without protection.
 * @param trustForwardedHeaders whether {@code X-Forwarded-For} may be believed. Off unless
 *                             the deployment really does terminate at a proxy that
 *                             overwrites it — see {@link CallerIdentityResolver}.
 * @param violationAuditCooldown how long one identity's rejections are collapsed into a
 *                             single audit event. Bounds audit growth under attack.
 */
@ConfigurationProperties("codearena.rate-limit")
@Validated
public record RateLimitProperties(

        boolean enabled,

        boolean trustForwardedHeaders,

        @NotNull @Valid Bucket loginOrigin,
        @NotNull @Valid Bucket loginAccount,
        @NotNull @Valid Bucket registration,
        @NotNull @Valid Bucket submission,
        @NotNull @Valid Bucket standings,
        @NotNull @Valid Bucket problemSearch,
        @NotNull @Valid Bucket adminRead,
        @NotNull @Valid Bucket adminWrite,

        @NotNull Duration violationAuditCooldown) {

    public RateLimitProperties {
        if (violationAuditCooldown != null && violationAuditCooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "codearena.rate-limit.violation-audit-cooldown must not be negative");
        }
    }

    /**
     * One token bucket's shape.
     *
     * @param capacity       how many requests are available at once — the burst. A bucket
     *                       starts full, so a caller who has been idle may spend the whole
     *                       capacity immediately.
     * @param refillInterval how long one token takes to come back. The sustained rate is
     *                       one request per interval; capacity only governs how far ahead
     *                       of that rate a caller may get.
     */
    public record Bucket(@Positive int capacity, @NotNull Duration refillInterval) {

        public Bucket {
            // Checked here as well as by the annotation, so the invariant holds however the
            // record is built. A bucket with no capacity rejects every request forever, and
            // an interval that rounds to zero divides by zero inside the Lua script -- both
            // would otherwise surface as an inexplicable outage rather than a bad setting.
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "a rate-limit capacity must be at least one request");
            }
            if (refillInterval != null && refillInterval.toMillis() < 1) {
                throw new IllegalArgumentException(
                        "a rate-limit refill interval must be at least one millisecond");
            }
        }

        public long refillIntervalMillis() {
            return refillInterval.toMillis();
        }

        /** Sustained requests per minute, for documentation and for the status view. */
        public double perMinute() {
            return 60_000.0 / refillIntervalMillis();
        }
    }

    /**
     * The bucket a policy uses.
     *
     * <p>An exhaustive switch, so adding a policy without configuring it does not compile.
     * A map keyed by policy name would push that mistake to runtime, where it would present
     * as a limit that silently never applies.
     */
    public Bucket bucketFor(RateLimitPolicy policy) {
        return switch (policy) {
            case LOGIN_ORIGIN -> loginOrigin;
            case LOGIN_ACCOUNT -> loginAccount;
            case REGISTRATION -> registration;
            case SUBMISSION -> submission;
            case STANDINGS -> standings;
            case PROBLEM_SEARCH -> problemSearch;
            case ADMIN_READ -> adminRead;
            case ADMIN_WRITE -> adminWrite;
        };
    }
}
