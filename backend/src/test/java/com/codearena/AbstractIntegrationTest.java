package com.codearena;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and real Redis, shared by every integration test in the JVM.
 *
 * <p>The containers are started once from a static initialiser and never explicitly
 * stopped — the singleton-container pattern — rather than being declared with
 * {@code @Container} and {@code @Testcontainers}. That annotation pair ties a container's
 * lifecycle to a single test <em>class</em>: it stops the container in the class's
 * teardown, so the next class in the run finds a dead database and every test in it fails
 * with "connection refused". Inheriting the annotations from a shared base does not change
 * that. Started here instead, the containers live for the whole JVM, and Testcontainers'
 * Ryuk sidecar removes them when it exits.
 *
 * <p>There is deliberately no in-memory substitute. The schema depends on PostgreSQL
 * behaviour that H2 does not reproduce — unique indexes over {@code lower(...)} for
 * case-insensitive identity, functional and trigram indexes, and the index names the
 * registration and authoring paths read to tell one constraint violation from another.
 * A test against a database that disagrees with production would be worse than no test.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Protected rather than package-private: the rate-limit outage suite lives in another
     * package and needs the container's address to put a breakable proxy in front of it.
     */
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * Empties the rate-limit buckets before every test in every suite.
     *
     * <p>Rate limiting stays <b>switched on</b> throughout the test run, so the interceptor,
     * the Lua script and the 429 path are exercised by the whole suite rather than only by
     * the tests written for them. What is removed is the interference: buckets are shared by
     * identity, every test signs in from the same loopback address, and a suite that ran a
     * hundred logins would otherwise start failing somewhere in the middle for reasons that
     * have nothing to do with what it is testing — and would fail differently depending on
     * which tests ran first.
     *
     * <p>Deleting only the limiter's own keys, not {@code FLUSHALL}: sessions, the submission
     * queue and the event channel also live in this Redis, and a blunt flush would make the
     * reset itself the cause of mysterious failures.
     *
     * <p>Runs before the subclass's own setup, which is where sign-in happens, because JUnit
     * runs superclass lifecycle methods first.
     */
    @BeforeEach
    void clearRateLimitBuckets() throws Exception {
        REDIS.execInContainer("redis-cli", "EVAL",
                "local keys = redis.call('KEYS', ARGV[1]);"
                        + " for i = 1, #keys do redis.call('DEL', keys[i]) end;"
                        + " return #keys",
                "0", "codearena:rl*");
    }

    /**
     * Where the application under test should look for Redis, when that is not the shared
     * container.
     *
     * <p>Exists for one suite: the rate-limit outage tests, which need to break the
     * connection on purpose and so put a proxy in front of a Redis of their own. Without a
     * hook here, a subclass declaring its own {@code @DynamicPropertySource} would be racing
     * this one for the same two keys — and losing quietly, which would leave those tests
     * passing against a perfectly healthy Redis while claiming to have broken it.
     */
    private static volatile String redisHostOverride;
    private static volatile Integer redisPortOverride;

    /** Called from a subclass's static initialiser, before its context is built. */
    protected static void redirectRedisTo(String host, int port) {
        redisHostOverride = host;
        redisPortOverride = port;
    }

    /** Called once the redirecting suite is finished, so later suites are unaffected. */
    protected static void stopRedirectingRedis() {
        redisHostOverride = null;
        redisPortOverride = null;
    }

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host",
                () -> redisHostOverride != null ? redisHostOverride : REDIS.getHost());
        registry.add("spring.data.redis.port",
                () -> redisPortOverride != null ? redisPortOverride : REDIS.getMappedPort(6379));

        // Cost 12 is right for production and far too slow to pay on every test login.
        // The encoder under test is the same DelegatingPasswordEncoder either way; only
        // the work factor differs.
        registry.add("codearena.security.bcrypt-strength", () -> 4);
    }

    /**
     * Empties every table, in an order the foreign keys allow.
     *
     * <p>The containers are shared by the whole JVM, so each test class starts with
     * whatever the previous one left behind. Every suite therefore clears the database in
     * its setup — and it has to clear <em>all</em> of it, not the tables it happens to care
     * about: contest_problems references problems with ON DELETE RESTRICT, so a suite that
     * deleted only problems would fail the moment a contest test had run before it.
     *
     * <p>Centralised here for that reason. When a later phase adds a table, one method
     * changes rather than five setups that fail one run at a time.
     *
     * <p>Order matters and is dependency-first: children before parents.
     *
     * <p><b>Two tables are deliberately absent.</b> {@code audit_events} and
     * {@code contest_rating_changes} are append-only — a trigger refuses DELETE — so clearing
     * them here would not merely fail, it would fail on every single suite. Neither leaks
     * between tests: both are keyed on {@code BIGSERIAL} identifiers that PostgreSQL never
     * reuses, so a fresh user or contest cannot inherit a previous suite's rows. Rows for
     * deleted users and contests are left behind on purpose; that is what an append-only log
     * is for. {@code user_ratings} is not listed either, because its foreign key to
     * {@code users} is ON DELETE CASCADE and it goes with them.
     */
    protected static void resetDatabase(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM submission_test_results");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM contest_participants");
        jdbc.update("DELETE FROM contest_problems");
        jdbc.update("DELETE FROM contests");
        jdbc.update("DELETE FROM problem_test_cases");
        jdbc.update("DELETE FROM problem_examples");
        jdbc.update("DELETE FROM problem_tags");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM users");
    }
}
