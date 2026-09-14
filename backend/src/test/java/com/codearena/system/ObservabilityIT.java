package com.codearena.system;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.shared.WorkerHeartbeat;
import com.codearena.support.BrowserClient;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability, and the rule that observing must not become disclosing.
 *
 * <p>Two things are being established. The first is that the new surfaces work: the metrics
 * endpoint serves, the worker register reads back what a worker writes, the status view
 * distinguishes a healthy system from a degraded one. The second matters more, and is the
 * reason a monitoring change needs security tests at all — <b>a metrics endpoint is a
 * read of the process's internals, and the easy way to build one is to expose everything
 * and filter afterwards.</b> Filtering afterwards is a rule somebody forgets to update; the
 * tests below assert what is <em>absent</em>, which is the only form of that claim that
 * keeps holding as the system grows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private WorkerDirectory workerDirectory;
    @Autowired private QueueHealth queueHealth;

    private BrowserClient admin;
    private BrowserClient user;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        clearWorkerRecords();
        // Both views cache for five seconds so a metrics scrape does not re-read the world
        // per gauge. These tests change the world and assert on it immediately, so they
        // start from a clean read rather than sleeping the cache out.
        workerDirectory.invalidate();
        queueHealth.invalidate();

        createAccount("boss", "boss@example.com", Role.ADMIN);
        createAccount("alice", "alice@example.com", Role.USER);
        admin = signIn("boss");
        user = signIn("alice");
    }

    // =============================================================== metrics exposure

    /**
     * The metrics endpoint is administrative. A scrape reveals traffic shape, dependency
     * behaviour and the versions of everything in the process — useful to an operator and
     * equally useful to somebody deciding what to try.
     */
    @Test
    void servesMetricsOnlyToAdministrators() {
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .as("anonymous")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(user.getAnyType("/actuator/prometheus").getStatusCode())
                .as("an ordinary user")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getAnyType("/actuator/prometheus").getStatusCode())
                .as("an administrator")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Mandatory: the metrics must not carry a credential, a cookie, a connection string or
     * a host path.
     *
     * <p>The assertions are on <b>values</b>, not on words. Matching the substring
     * "password" fails on {@code UsernamePasswordAuthenticationToken}, and "csrf" on
     * {@code spring_security_filterchains_csrf_after_total} — both of which are Spring
     * Security's own <em>metric names</em> and disclose nothing at all. A test written that
     * way does not find leaks; it finds vocabulary, and the pressure it creates is to weaken
     * it until it passes. So the things that would actually be damaging are named here
     * explicitly and asserted to be absent.
     */
    @Test
    void putsNoSecretIntoTheMetrics() {
        String metrics = admin.getAnyType("/actuator/prometheus").getBody();

        assertThat(metrics).isNotBlank();
        assertThat(metrics)
                .as("the account password used by this suite")
                .doesNotContain(PASSWORD);
        assertThat(metrics)
                .as("a database connection string names a host, a port and often a user")
                .doesNotContain("jdbc:")
                .doesNotContain("postgresql://");
        assertThat(metrics)
                .as("the session cookie carried by the very request that fetched this")
                .doesNotContain(admin.cookie("CODEARENA_SESSION") == null
                        ? "no-session-cookie-present" : admin.cookie("CODEARENA_SESSION"));
        assertThat(metrics)
                .as("host and container internals")
                .doesNotContain("docker.sock")
                .doesNotContain("/var/run")
                .doesNotContain("seccomp");
    }

    /**
     * The cardinality rule, asserted rather than assumed.
     *
     * <p>A metric tagged with a user, a submission or a problem creates one time series per
     * value. The consequence is not untidiness: it is that a busy evening quietly turns the
     * monitoring system into the outage, at the exact moment somebody needs to look at it.
     * Identifiers belong in logs, one line each.
     */
    @Test
    void putsNoIdentifierIntoAMetricLabel() {
        String alice = userRepository.findByUsernameOrEmail("alice").orElseThrow()
                .getPublicId().toString();

        String metrics = admin.getAnyType("/actuator/prometheus").getBody();

        assertThat(metrics)
                .doesNotContain(alice)
                .doesNotContain("alice")
                .doesNotContain("boss");
    }

    /** The endpoints that could print configuration or memory are not merely restricted. */
    @Test
    void doesNotExposeTheDangerousActuatorEndpointsAtAll() {
        for (String endpoint : List.of("env", "configprops", "heapdump", "threaddump",
                "loggers", "mappings", "beans", "caches", "scheduledtasks")) {
            assertThat(admin.getAnyType("/actuator/" + endpoint).getStatusCode())
                    .as("/actuator/%s must not exist, even for an administrator", endpoint)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    /** The business metrics are actually registered, not merely absent of secrets. */
    @Test
    void publishesTheMetricsThatDescribeWhatTheSystemDid() {
        String metrics = admin.getAnyType("/actuator/prometheus").getBody();

        assertThat(metrics)
                .contains("codearena_auth_attempts")
                .contains("codearena_queue_depth")
                .contains("codearena_workers")
                .contains("http_server_requests");
    }

    // =============================================================== health semantics

    /**
     * Liveness must never depend on a dependency. A probe that fails when PostgreSQL is
     * slow turns one outage into a restart loop, and restarting a healthy API server has
     * never repaired a database.
     */
    @Test
    void keepsLivenessIndependentOfEveryDependency() {
        ResponseEntity<String> liveness =
                restTemplate.getForEntity("/actuator/health/liveness", String.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("UP");
        assertThat(liveness.getBody())
                .as("a bare status, with no component detail for an unauthenticated caller")
                .doesNotContain("components")
                .doesNotContain("PostgreSQL")
                .doesNotContain("version");
    }

    /** Readiness means "can serve traffic", which here requires the database and Redis. */
    @Test
    void makesReadinessDependOnWhatServingActuallyNeeds() {
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> detail = admin.getJson("/actuator/health/readiness").getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) detail.get("components");
        assertThat(components)
                .as("an instance without its database cannot serve, and must leave rotation")
                .containsKeys("db", "redis");
    }

    // =============================================================== the worker register

    @Test
    void reportsNoWorkersWhenNoneHaveRegistered() {
        assertThat(workerDirectory.workers()).isEmpty();

        Map<String, Object> status = status();
        assertThat((List<?>) status.get("workers")).isEmpty();
    }

    @Test
    void readsBackWhatAWorkerPublishesAboutItself() {
        writeHeartbeat("worker-a", Instant.now(), 2, 1, 42, 0, false);

        Map<String, Object> worker = workers().getFirst();

        assertThat(worker).containsEntry("id", "worker-a");
        assertThat(worker).containsEntry("healthy", true);
        assertThat(worker).containsEntry("concurrency", 2);
        assertThat(worker).containsEntry("activeJobs", 1);
        assertThat(worker).containsEntry("judged", 42);
    }

    /**
     * The case a container health check cannot see: the process is up as far as anything
     * outside it knows, and it has stopped doing the work.
     */
    @Test
    void reportsAWorkerThatHasStoppedReportingAsStale() {
        writeHeartbeat("worker-b", Instant.now().minus(WorkerHeartbeat.STALE_AFTER).minusSeconds(30),
                2, 0, 10, 1, false);

        assertThat(workers().getFirst()).containsEntry("healthy", false);
    }

    /** A planned stop is not a fault, and must not look like one. */
    @Test
    void distinguishesAWorkerThatIsLeavingDeliberately() {
        writeHeartbeat("worker-c", Instant.now(), 2, 1, 5, 0, true);

        Map<String, Object> worker = workers().getFirst();
        assertThat(worker).containsEntry("draining", true);
        assertThat(worker).containsEntry("healthy", true);
    }

    /** A worker that shut down cleanly removes its record and simply stops appearing. */
    @Test
    void forgetsAWorkerThatDeregistered() {
        writeHeartbeat("worker-d", Instant.now(), 1, 0, 0, 0, false);
        assertThat(workerDirectory.workers()).hasSize(1);

        redis.delete(WorkerHeartbeat.keyFor("worker-d"));
        workerDirectory.invalidate();

        assertThat(workerDirectory.workers()).isEmpty();
    }

    /** Every record expires by itself, so a vanished worker cannot linger indefinitely. */
    @Test
    void expiresWorkerRecords() {
        writeHeartbeat("worker-e", Instant.now(), 1, 0, 0, 0, false);

        assertThat(redis.getExpire(WorkerHeartbeat.keyFor("worker-e")))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(WorkerHeartbeat.RECORD_TTL.toSeconds());
    }

    // =============================================================== queue health

    @Test
    void reportsQueueDepthAndTheAgeThatMakesItMeaningful() {
        Map<String, Object> queue = queue();

        assertThat(queue).containsKeys(
                "pending", "processing", "oldestPendingAgeSeconds", "retrying", "systemErrorsLastHour");
    }

    /**
     * Depth alone cannot tell a busy queue from a stopped one; age can. This asserts the
     * field is real rather than a placeholder.
     */
    @Test
    void measuresHowLongTheOldestSubmissionHasBeenWaiting() {
        insertQueuedSubmission(Duration.ofMinutes(9));
        queueHealth.invalidate();

        Long age = queueHealth.measure().oldestPendingAgeSeconds();

        assertThat(age).isNotNull();
        assertThat(age).isBetween(500L, 600L);
    }

    @Test
    void countsTheJudgesOwnFailuresSeparatelyFromVerdicts() {
        insertSystemError();

        queueHealth.invalidate();

        assertThat(queueHealth.measure().systemErrorsLastHour()).isEqualTo(1);
    }

    // =============================================================== the status view

    /**
     * The state that could not be seen before: every request succeeds, every dependency
     * answers, and nothing is being judged.
     */
    @Test
    void callsItselfDegradedWhenWorkIsWaitingAndNobodyIsJudging() {
        insertQueuedSubmission(Duration.ofMinutes(1));
        redis.opsForList().leftPush("codearena:submissions:pending", java.util.UUID.randomUUID().toString());
        queueHealth.invalidate();

        assertThat(status()).containsEntry("state", "DEGRADED");
    }

    /** An idle system with no workers is idle, not an incident. Paging for it would be noise. */
    @Test
    void doesNotCallAnIdleSystemDegraded() {
        redis.delete("codearena:submissions:pending");
        queueHealth.invalidate();

        assertThat(status()).containsEntry("state", "READY");
    }

    @Test
    void reportsUptimeAndVersionWithoutRevealingAnythingElse() {
        Map<String, Object> status = status();

        assertThat(status).containsKeys("version", "uptimeSeconds", "state", "audit");
        assertThat(((Number) status.get("uptimeSeconds")).longValue()).isNotNegative();
    }

    /**
     * Mandatory, and the same rule as for the metrics: the status view is curated, so what
     * it does not say is the part worth testing.
     */
    @Test
    void putsNoSecretOrHostDetailIntoTheStatusView() {
        writeHeartbeat("worker-f", Instant.now(), 2, 0, 0, 0, false);

        String body = admin.get("/api/admin/system/status", String.class).getBody();

        assertThat(body)
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("jdbc:")
                .doesNotContainIgnoringCase("docker")
                .doesNotContainIgnoringCase("/var/run")
                .doesNotContainIgnoringCase("/app")
                .doesNotContainIgnoringCase("seccomp")
                .doesNotContainIgnoringCase("apparmor")
                .doesNotContain(PASSWORD);
    }

    @Test
    void keepsTheStatusViewAdministrative() {
        assertThat(user.get("/api/admin/system/status", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.getForEntity("/api/admin/system/status", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =============================================================== helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> status() {
        return admin.getJson("/api/admin/system/status").getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> workers() {
        List<Map<String, Object>> workers = (List<Map<String, Object>>) status().get("workers");
        assertThat(workers).isNotEmpty();
        return workers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queue() {
        return (Map<String, Object>) status().get("queue");
    }

    private void writeHeartbeat(String id, Instant lastSeen, int concurrency, int active,
                                long judged, long failed, boolean draining) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put(WorkerHeartbeat.FIELD_ID, id);
        record.put(WorkerHeartbeat.FIELD_VERSION, "test");
        record.put(WorkerHeartbeat.FIELD_STARTED_AT,
                Long.toString(Instant.now().minusSeconds(60).toEpochMilli()));
        record.put(WorkerHeartbeat.FIELD_LAST_SEEN_AT, Long.toString(lastSeen.toEpochMilli()));
        record.put(WorkerHeartbeat.FIELD_CONCURRENCY, Integer.toString(concurrency));
        record.put(WorkerHeartbeat.FIELD_ACTIVE, Integer.toString(active));
        record.put(WorkerHeartbeat.FIELD_JUDGED, Long.toString(judged));
        record.put(WorkerHeartbeat.FIELD_FAILED, Long.toString(failed));
        record.put(WorkerHeartbeat.FIELD_DRAINING, Boolean.toString(draining));

        String key = WorkerHeartbeat.keyFor(id);
        redis.opsForHash().putAll(key, record);
        redis.expire(key, WorkerHeartbeat.RECORD_TTL);
        // The register is read through a five-second cache so a metrics scrape does not
        // re-scan the keyspace per gauge. A test writes and reads in the same breath.
        workerDirectory.invalidate();
    }

    private void clearWorkerRecords() {
        Set<String> keys = redis.keys(WorkerHeartbeat.KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete("codearena:submissions:pending");
        redis.delete("codearena:submissions:processing");
    }

    /** A submission sitting in the queue, enqueued a known time ago. */
    private void insertQueuedSubmission(Duration waitedFor) {
        seedProblemAndUser();
        jdbc.update("""
                INSERT INTO submissions
                    (public_id, problem_id, user_id, language, source_code, status,
                     enqueued_at, created_at, updated_at, attempts)
                VALUES (gen_random_uuid(),
                        (SELECT id FROM problems LIMIT 1),
                        (SELECT id FROM users WHERE username = 'alice'),
                        'PYTHON', 'print(1)', 'QUEUED', now() - ?::interval,
                        now() - ?::interval, now(), 1)
                """, waitedFor.toSeconds() + " seconds", waitedFor.toSeconds() + " seconds");
    }

    private void insertSystemError() {
        seedProblemAndUser();
        jdbc.update("""
                INSERT INTO submissions
                    (public_id, problem_id, user_id, language, source_code, status,
                     created_at, updated_at, finished_at, attempts)
                VALUES (gen_random_uuid(),
                        (SELECT id FROM problems LIMIT 1),
                        (SELECT id FROM users WHERE username = 'alice'),
                        'PYTHON', 'print(1)', 'SYSTEM_ERROR', now(), now(), now(), 3)
                """);
    }

    /** The minimum a submission row needs to exist: an author and a problem. */
    private void seedProblemAndUser() {
        Integer problems = jdbc.queryForObject("SELECT count(*) FROM problems", Integer.class);
        if (problems != null && problems > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO problems (public_id, slug, title, statement, input_format,
                                      output_format, constraints, difficulty, status,
                                      time_limit_ms, memory_limit_mb, created_by,
                                      created_at, updated_at)
                VALUES (gen_random_uuid(), 'probe', 'Probe', 'Body', 'In', 'Out', 'None',
                        'EASY', 'PUBLISHED', 2000, 256,
                        (SELECT id FROM users WHERE username = 'boss'), now(), now())
                """);
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(
                User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private BrowserClient signIn(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        assertThat(client.postJson("/api/auth/login", new LoginRequest(username, PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return client;
    }
}
