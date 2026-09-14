package com.codearena.ratelimit;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.support.BrowserClient;
import com.codearena.support.FailableTcpProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to rate limiting when Redis is not there — and what happens when it comes
 * back.
 *
 * <h2>Why this test exists in this shape</h2>
 * "Fail open or fail closed" is an architectural claim (ADR-039), and a claim in a document
 * is worth nothing until something fails on purpose and the documented thing happens. So the
 * connection to Redis is genuinely broken here, not mocked: a proxy in front of it drops
 * every live socket and refuses new ones, which is what the application sees when Redis
 * dies.
 *
 * <h2>What a restart is simulated as, precisely</h2>
 * A restart does two things to this subsystem: it drops every connection, and it destroys
 * every bucket. Both are done here, and enforcement is asserted to resume afterwards —
 * the point being that nothing gets permanently wedged and that an expired or vanished
 * bucket simply starts full again. A genuine {@code docker compose restart redis} is not
 * possible against a shared Testcontainers instance without breaking every test class that
 * follows; that version of the check is in the end-to-end script, against the live stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "codearena.rate-limit.registration.capacity=3",
        "codearena.rate-limit.registration.refill-interval=60s",
        // Lettuce must give up quickly, or a broken connection is a slow test rather than a
        // failed request. Production uses three seconds; the behaviour is the same either way.
        "spring.data.redis.timeout=500ms"
})
class RateLimitRedisOutageIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    /**
     * A Redis of this suite's own, behind a proxy that can be switched off.
     *
     * <p>Private rather than the shared container, because these tests deliberately sever a
     * connection and the shared one is in use by every other suite in the JVM. Breaking
     * something on purpose should never be able to break something else by accident.
     *
     * <p>Both are set up in a static initialiser and the redirect is registered there too,
     * so it is in place before the Spring context is built and reads the address.
     */
    private static final GenericContainer<?> OWN_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final FailableTcpProxy REDIS_PROXY;

    static {
        OWN_REDIS.start();
        try {
            REDIS_PROXY = new FailableTcpProxy(OWN_REDIS.getHost(), OWN_REDIS.getMappedPort(6379));
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the Redis proxy", e);
        }
        redirectRedisTo("localhost", REDIS_PROXY.port());
    }

    /** Later suites must find the shared Redis again, not this one's proxy. */
    @AfterAll
    static void releaseTheRedirect() {
        stopRedirectingRedis();
        REDIS_PROXY.close();
        OWN_REDIS.stop();
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private StringRedisTemplate redis;

    @AfterEach
    void healRedis() {
        REDIS_PROXY.restoreConnection();
    }

    // =============================================================== fail closed

    /**
     * Registration is a closed policy: with no way to know whether an allowance remains, the
     * request is refused.
     *
     * <p>The cost is real — registration stops working while Redis is down — and it is the
     * intended trade. Every registration writes a permanent row to the authoritative
     * database and consumes a username, so "allow unlimited account creation because the
     * abuse control is unavailable" is the one outcome worth avoiding. It is also the moment
     * an attacker would choose, which is precisely why the control must not be the thing
     * that disappears under load.
     */
    @Test
    void refusesRegistrationWhileTheLimiterCannotSeeItsState() {
        resetDatabase(jdbc);
        REDIS_PROXY.breakConnection();

        ResponseEntity<Map<String, Object>> response = register("hopeful");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsEntry("error", "RATE_LIMITED");
        assertThat(userCount()).as("and no account was created").isZero();
    }

    /** Even refusing, it must say nothing about why — no Redis, no host, no stack trace. */
    @Test
    void saysNothingAboutTheInfrastructureWhileItIsFailing() {
        REDIS_PROXY.breakConnection();

        Map<String, Object> body = register("hopeful-two").getBody();

        assertThat(String.valueOf(body))
                .doesNotContainIgnoringCase("redis")
                .doesNotContainIgnoringCase("connection")
                .doesNotContainIgnoringCase("lettuce")
                .doesNotContainIgnoringCase("localhost")
                .doesNotContainIgnoringCase("exception");
        assertThat(body).containsEntry("message", RateLimitHeaders.MESSAGE);
    }

    /** A closed policy still hands back a usable wait rather than an empty refusal. */
    @Test
    void stillTellsAClosedPolicyCallerWhenToComeBack() {
        REDIS_PROXY.breakConnection();

        RateLimitDecision decision = rateLimitService.check(
                RateLimitPolicy.REGISTRATION, CallerIdentity.ofClient("203.0.113.4"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    // =============================================================== fail open

    /**
     * Standings are an open policy: the limiter exists to stop a script from making the
     * database recompute a scoreboard too often, and that is a comfort rather than a
     * security boundary. Losing it for the duration of an outage is better than turning a
     * Redis problem into a second outage of the contest scoreboard.
     *
     * <p>Asserted against the service rather than over HTTP, deliberately. Reaching the
     * standings endpoint needs a session, and sessions live in Redis — so with Redis down
     * the request would be refused by authentication long before the limiter had an opinion.
     * That is worth stating rather than hiding behind a test that cannot distinguish the
     * two: <b>for authenticated traffic, "Redis is down" already means "the API is down",
     * which is what makes failing the security-critical policies closed nearly free.</b>
     */
    @Test
    void keepsServingAnOpenPolicyWhileTheLimiterCannotSeeItsState() {
        REDIS_PROXY.breakConnection();

        RateLimitDecision decision = rateLimitService.check(
                RateLimitPolicy.STANDINGS, CallerIdentity.ofUser(UUID.randomUUID().toString()));

        assertThat(decision.allowed())
                .as("an unavailable comfort control must not become an outage")
                .isTrue();
    }

    /** Both modes at once, so the difference is the policy and nothing else. */
    @Test
    void appliesTheFailureModeThePolicyDeclares() {
        REDIS_PROXY.breakConnection();
        CallerIdentity caller = CallerIdentity.ofClient("203.0.113.5");

        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            boolean allowed = rateLimitService.check(policy, identityFor(policy, caller)).allowed();

            assertThat(allowed)
                    .as("%s declares %s", policy.id(), policy.failureMode())
                    .isEqualTo(policy.failureMode() == RateLimitPolicy.FailureMode.OPEN);
        }
    }

    // =============================================================== recovery

    /**
     * The other half of the claim: an outage must not leave anything permanently broken.
     */
    @Test
    void enforcesLimitsAgainOnceRedisComesBack() {
        resetDatabase(jdbc);
        REDIS_PROXY.breakConnection();
        assertThat(register("during-the-outage").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        REDIS_PROXY.restoreConnection();
        awaitRedisRecovery();

        // Three allowed, then the limit holds -- so the limiter is not merely reachable
        // again, it is counting again.
        for (int i = 0; i < 3; i++) {
            assertThat(register("after-" + i).getStatusCode())
                    .as("registration %d after recovery", i)
                    .isEqualTo(HttpStatus.CREATED);
        }
        assertThat(register("after-too-many").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Losing every bucket is not a failure mode that needs handling: an absent bucket is a
     * full one, which is the same state it would have reached by waiting. That is what makes
     * a Redis restart survivable without any recovery code at all.
     */
    @Test
    void startsFromAFullAllowanceWhenTheStateIsGone() throws Exception {
        resetDatabase(jdbc);
        CallerIdentity caller = CallerIdentity.ofClient("203.0.113.6");

        for (int i = 0; i < 3; i++) {
            assertThat(rateLimitService.check(RateLimitPolicy.REGISTRATION, caller).allowed()).isTrue();
        }
        assertThat(rateLimitService.check(RateLimitPolicy.REGISTRATION, caller).allowed()).isFalse();

        // Both halves of what a restart does: the connection drops, and the state vanishes.
        REDIS_PROXY.breakConnection();
        clearRateLimitState();
        REDIS_PROXY.restoreConnection();
        awaitRedisRecovery();

        RateLimitDecision afterRestart = rateLimitService.check(RateLimitPolicy.REGISTRATION, caller);

        assertThat(afterRestart.allowed())
                .as("a vanished bucket is a full one, not a wedged one")
                .isTrue();
        assertThat(afterRestart.remaining()).isEqualTo(2);
    }

    // =============================================================== helpers

    /**
     * Waits for the client to reconnect, and bounds how long that may take.
     *
     * <p>Recovery is not instantaneous and the tests should not pretend it is: the Redis
     * client reconnects on its own schedule, backing off between attempts, so for a second
     * or two after the server returns the application still cannot reach it and closed
     * policies still refuse. That window is real, it is what an operator would see, and it
     * is <em>bounded</em> — which is the actual claim being tested. Waiting here asserts
     * that recovery is automatic and prompt; the ten-second ceiling asserts it is not
     * indefinite, so a regression that left the pool permanently broken would fail rather
     * than hang.
     */
    private void awaitRedisRecovery() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                redis.hasKey("codearena:rl:recovery-probe");
                return;
            } catch (RuntimeException stillDown) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for Redis", e);
                }
            }
        }
        throw new IllegalStateException("Redis did not become reachable again within ten seconds");
    }

    /** Client-scoped policies need a client; user-scoped ones need a user. */
    private static CallerIdentity identityFor(RateLimitPolicy policy, CallerIdentity client) {
        return policy.scope() == RateLimitPolicy.Scope.USER
                ? CallerIdentity.ofUser("00000000-0000-4000-8000-000000000001")
                : client;
    }

    private void clearRateLimitState() throws Exception {
        OWN_REDIS.execInContainer("redis-cli", "EVAL",
                "local keys = redis.call('KEYS', ARGV[1]);"
                        + " for i = 1, #keys do redis.call('DEL', keys[i]) end;"
                        + " return #keys",
                "0", "codearena:rl*");
    }

    private ResponseEntity<Map<String, Object>> register(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        return client.postJson("/api/auth/register",
                new RegistrationRequest(username, username + "@example.com", PASSWORD));
    }

    private long userCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }
}
