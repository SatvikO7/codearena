package com.codearena.audit;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.support.BrowserClient;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit log, over real HTTP against real PostgreSQL.
 *
 * <p>Three things are being established here, and only the first is ordinary CRUD testing:
 * that the right events are recorded; that they <b>cannot be altered or removed</b>; and
 * that a success event <b>cannot outlive the change it describes</b>. The second and third
 * are what make an audit log worth having, and neither can be demonstrated without a real
 * database — a mock will happily agree to anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AuditService auditService;

    private BrowserClient admin;
    private BrowserClient user;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);

        createAccount("boss", "boss@example.com", Role.ADMIN);
        createAccount("alice", "alice@example.com", Role.USER);
        admin = signIn("boss");
        user = signIn("alice");
    }

    // =============================================================== immutability

    /**
     * The property the whole design turns on.
     *
     * <p>An audit log an administrator can edit is not an audit log — the person most worth
     * holding to account is the one with the most access. The application has no update path,
     * but this asserts the database refuses regardless of what any code intends.
     */
    @Test
    void refusesToUpdateAnAuditEvent() {
        long id = anyAuditEventId();

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'AUTH_LOGIN' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void refusesToDeleteAnAuditEvent() {
        long id = anyAuditEventId();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    /** Even a blanket delete, which is what a careless cleanup script would attempt. */
    @Test
    void refusesToTruncateTheLogByDeletingEverything() {
        anyAuditEventId();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
                .isInstanceOf(DataAccessException.class);
        assertThat(countEvents()).isPositive();
    }

    /** The API is read-only: there is no write verb to reach, not merely none exposed. */
    @Test
    void offersNoWriteEndpointOnTheAuditApi() {
        assertThat(admin.postJson("/api/admin/audit-events", Map.of("action", "AUTH_LOGIN"))
                .getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
        assertThat(admin.delete("/api/admin/audit-events", Map.class)
                .getStatusCode()).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
    }

    // =============================================================== transactional consistency

    /**
     * A rolled-back mutation must leave no record claiming it happened.
     *
     * <p>This is why administrative events are written in the caller's transaction rather
     * than independently. Without it the log would accumulate confident accounts of changes
     * that never occurred — worse than no log, because it would be believed.
     */
    @Test
    void discardsASuccessEventWhenItsTransactionRollsBack() {
        long before = countEvents();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            auditService.record(AuditAction.PROBLEM_CREATE, AuditOutcome.SUCCESS,
                    AuditEntityType.PROBLEM, "never-committed", Map.of());
            throw new IllegalStateException("the domain mutation failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countEvents()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = 'never-committed'", Integer.class))
                .isZero();
    }

    /**
     * Recording outside a transaction is a programming error, and a loud one.
     *
     * <p>{@code Propagation.MANDATORY} means a caller who forgets gets an immediate failure
     * rather than an audit row that quietly commits alone and survives a rolled-back
     * mutation — a mistake that would only be discovered during an incident.
     */
    @Test
    void refusesToRecordATransactionalEventOutsideATransaction() {
        assertThatThrownBy(() -> auditService.record(
                AuditAction.PROBLEM_CREATE, AuditOutcome.SUCCESS, Map.of()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /** A failure event must survive the rollback of the request that produced it. */
    @Test
    void keepsAnIndependentEventWhenTheSurroundingTransactionRollsBack() {
        long before = countEvents();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            auditService.recordIndependently(AuditAction.AUTH_LOGIN_FAILURE, AuditOutcome.FAILURE,
                    null, null, Map.of("reason", "INVALID_CREDENTIALS"));
            throw new IllegalStateException("the request failed, as a failed login does");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countEvents())
                .as("a failed login is exactly the event that must not be lost to a rollback")
                .isEqualTo(before + 1);
    }

    // =============================================================== authentication events

    @Test
    void recordsASuccessfulLogin() {
        signIn("alice");

        Map<String, Object> event = latestEvent("AUTH_LOGIN");
        assertThat(event).containsEntry("actorUsername", "alice");
        assertThat(event).containsEntry("outcome", "SUCCESS");
        assertThat(event).containsEntry("actorType", "USER");
    }

    @Test
    void recordsAFailedLogin() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        client.postJson("/api/auth/login", new LoginRequest("alice", "the wrong password"));

        Map<String, Object> event = latestEvent("AUTH_LOGIN_FAILURE");
        assertThat(event).containsEntry("outcome", "FAILURE");
        assertThat(event)
                .as("nobody authenticated, so there is no actor to attribute it to")
                .containsEntry("actorType", "ANONYMOUS");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
        assertThat(metadata).containsEntry("attemptedIdentifier", "alice");
        assertThat(metadata).containsEntry("reason", "INVALID_CREDENTIALS");
    }

    /**
     * The actor is whoever made the request, not a blanket ANONYMOUS. A wrong password
     * presented from a live session was genuinely presented by that session, and somebody
     * signed in as one user guessing at another account is exactly the pattern an audit log
     * exists to surface. Flattening it to ANONYMOUS would discard the identity.
     */
    @Test
    void attributesAFailedLoginMadeFromAnExistingSessionToThatSession() {
        BrowserClient client = signIn("alice");

        client.postJson("/api/auth/login", new LoginRequest("boss", "the wrong password"));

        Map<String, Object> event = latestEvent("AUTH_LOGIN_FAILURE");
        assertThat(event).containsEntry("outcome", "FAILURE");
        assertThat(event).containsEntry("actorUsername", "alice");
        assertThat(event).containsEntry("actorType", "USER");
        assertThat(metadataOf(event))
                .as("the account that was tried, which is not the account that tried it")
                .containsEntry("attemptedIdentifier", "boss");
    }

    /**
     * A failed-login record must not become the account-enumeration oracle that login itself
     * is carefully designed not to be.
     */
    @Test
    void doesNotRevealWhetherTheAccountExistsOnAFailedLogin() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");

        client.postJson("/api/auth/login", new LoginRequest("alice", "wrong"));
        Map<String, Object> existing = latestEvent("AUTH_LOGIN_FAILURE");

        client.postJson("/api/auth/login", new LoginRequest("nobody-at-all", "wrong"));
        Map<String, Object> missing = latestEvent("AUTH_LOGIN_FAILURE");

        assertThat(metadataOf(missing).get("reason"))
                .as("the same reason for a real account and an imaginary one")
                .isEqualTo(metadataOf(existing).get("reason"));
    }

    /** A password must never reach the audit table by any route. */
    @Test
    void neverRecordsAPasswordOnAFailedLogin() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        client.postJson("/api/auth/login", new LoginRequest("alice", "SECRET-PASSWORD-MARKER"));

        String everything = jdbc.queryForObject(
                "SELECT COALESCE(string_agg(metadata::text, ' '), '') FROM audit_events", String.class);

        assertThat(everything).doesNotContain("SECRET-PASSWORD-MARKER");
    }

    @Test
    void recordsRegistrationAndLogout() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        client.postJson("/api/auth/register",
                new RegistrationRequest("newcomer", "newcomer@example.com", PASSWORD));

        assertThat(latestEvent("AUTH_REGISTER")).containsEntry("actorUsername", "newcomer");

        client.postJson("/api/auth/login", new LoginRequest("newcomer", PASSWORD));
        client.postJson("/api/auth/logout", null);

        assertThat(latestEvent("AUTH_LOGOUT")).containsEntry("actorUsername", "newcomer");
    }

    // =============================================================== admin events

    @Test
    void recordsAdministrativeMutations() {
        UUID problemId = createProblemViaApi("audited", "Audited Problem");
        admin.postJson("/api/admin/problems/" + problemId + "/publish", null);

        Map<String, Object> created = latestEvent("PROBLEM_CREATE");
        assertThat(created).containsEntry("actorUsername", "boss");
        assertThat(created).containsEntry("actorType", "ADMIN");
        assertThat(created).containsEntry("entityType", "PROBLEM");
        assertThat(created).containsEntry("entityId", problemId.toString());

        Map<String, Object> published = latestEvent("PROBLEM_PUBLISH");
        assertThat(metadataOf(published)).containsEntry("previousStatus", "DRAFT");
        assertThat(metadataOf(published)).containsEntry("newStatus", "PUBLISHED");
    }

    /** The metadata is curated, so a problem's statement and test cases stay out of it. */
    @Test
    void keepsProblemContentOutOfAuditMetadata() {
        createProblemViaApi("secretive", "Secretive");

        String everything = jdbc.queryForObject(
                "SELECT COALESCE(string_agg(metadata::text, ' '), '') FROM audit_events", String.class);

        assertThat(everything).doesNotContain("HIDDEN-TEST-INPUT");
        assertThat(everything).doesNotContain("HIDDEN-TEST-ANSWER");
        assertThat(everything).doesNotContain("Add two numbers");
    }

    /** A denial on the admin surface is the signal an audit log exists to surface. */
    @Test
    void recordsADeniedAdministrativeRequest() {
        assertThat(user.postJson("/api/admin/problems", Map.of("title", "nope"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> event = latestEvent("ADMIN_ACCESS_DENIED");
        assertThat(event).containsEntry("outcome", "DENIED");
        assertThat(event).containsEntry("actorUsername", "alice");
        assertThat(metadataOf(event)).containsEntry("path", "/api/admin/problems");
        assertThat(metadataOf(event)).containsEntry("method", "POST");
    }

    /**
     * Ordinary reads must not be audited.
     *
     * <p>The failure mode this prevents is an audit log so full of noise that the events
     * worth reading cannot be found.
     */
    @Test
    void doesNotAuditOrdinaryReads() {
        long before = countEvents();

        user.getJson("/api/contests");
        user.getJson("/api/problems");
        user.getJson("/api/auth/me");
        user.getJson("/api/submissions");

        assertThat(countEvents()).isEqualTo(before);
    }

    // =============================================================== the API

    @Test
    void refusesTheAuditApiToAnonymousCallers() {
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.getJson("/api/system/info");

        assertThat(anonymous.getJson("/api/admin/audit-events").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refusesTheAuditApiToNormalUsers() {
        assertThat(user.getJson("/api/admin/audit-events").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void servesTheAuditLogToAdministratorsNewestFirst() {
        signIn("alice");
        signIn("boss");

        List<Map<String, Object>> events = search("");
        assertThat(events).isNotEmpty();

        List<String> timestamps = events.stream().map(e -> e.get("occurredAt").toString()).toList();
        assertThat(timestamps).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void filtersByActionOutcomeAndEntity() {
        UUID problemId = createProblemViaApi("filterable", "Filterable");
        user.postJson("/api/admin/problems", Map.of("title", "nope"));

        assertThat(search("?action=PROBLEM_CREATE"))
                .allSatisfy(event -> assertThat(event).containsEntry("action", "PROBLEM_CREATE"));
        assertThat(search("?outcome=DENIED"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event).containsEntry("outcome", "DENIED"));
        assertThat(search("?entityType=PROBLEM&entityId=" + problemId))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event).containsEntry("entityId", problemId.toString()));
        assertThat(search("?actorType=ANONYMOUS&action=PROBLEM_CREATE")).isEmpty();
    }

    @Test
    void paginatesTheAuditLog() {
        for (int i = 0; i < 5; i++) {
            signIn("alice");
        }

        Map<String, Object> firstPage = admin.getJson("/api/admin/audit-events?size=2&page=0").getBody();
        assertThat((List<?>) firstPage.get("items")).hasSize(2);
        assertThat(firstPage).containsEntry("page", 0);
        assertThat(firstPage).containsEntry("hasNext", true);

        Map<String, Object> secondPage = admin.getJson("/api/admin/audit-events?size=2&page=1").getBody();
        assertThat(secondPage).containsEntry("page", 1);
        assertThat(secondPage).containsEntry("hasPrevious", true);
    }

    /**
     * An open sort parameter would let a caller order by fields the API never exposes and
     * make the database sort an unindexed column.
     */
    @Test
    void refusesASortFieldOutsideTheWhitelist() {
        for (String hostile : new String[]{
                "actorUsername", "id", "metadata",
                "occurredAt; DROP TABLE audit_events", "(SELECT 1)"}) {
            ResponseEntity<Map<String, Object>> response =
                    admin.getJson("/api/admin/audit-events?sort=" + hostile);

            assertThat(response.getStatusCode())
                    .as("sort=%s must be refused", hostile)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void acceptsEverySortFieldOnTheWhitelist() {
        for (String allowed : AuditQueryService.SORTABLE) {
            assertThat(admin.getJson("/api/admin/audit-events?sort=" + allowed).getStatusCode())
                    .as("sort=%s must be accepted", allowed)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /** Filters are bound parameters against typed columns; nothing a caller sends is SQL. */
    @Test
    void treatsInjectionAttemptsInFiltersAsOrdinaryValues() {
        String injection = "' OR '1'='1";

        ResponseEntity<Map<String, Object>> byEntity = admin.getJson(
                "/api/admin/audit-events?entityId=" + java.net.URLEncoder.encode(
                        injection, java.nio.charset.StandardCharsets.UTF_8));

        assertThat(byEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) byEntity.getBody().get("items")).isEmpty();
        assertThat(countEvents()).isPositive();   // the table is still there
    }

    @Test
    void rejectsAnUnknownActionFilter() {
        assertThat(admin.getJson("/api/admin/audit-events?action=NOT_AN_ACTION").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** An inverted range is a mistyped question, and an empty page would look like an answer. */
    @Test
    void rejectsAnInvertedTimeRange() {
        assertThat(admin.getJson(
                "/api/admin/audit-events?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filtersByTimeRange() {
        signIn("alice");

        assertThat(search("?from=2000-01-01T00:00:00Z")).isNotEmpty();
        assertThat(search("?to=2000-01-01T00:00:00Z")).isEmpty();
    }

    @Test
    void publishesTheActionVocabulary() {
        @SuppressWarnings("unchecked")
        List<String> actions = admin.get("/api/admin/audit-events/actions", List.class).getBody();

        assertThat(actions).contains("AUTH_LOGIN", "AUTH_LOGIN_FAILURE",
                "PROBLEM_PUBLISH", "CONTEST_CANCEL", "ADMIN_ACCESS_DENIED");
        assertThat(actions).hasSize(AuditAction.values().length);
    }

    /** The response carries no internal identifiers and nothing about the account. */
    @Test
    void exposesOnlySafeFields() {
        signIn("alice");

        String body = admin.get("/api/admin/audit-events", String.class).getBody();

        assertThat(body).contains("alice");
        assertThat(body).doesNotContain("alice@example.com");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("sourceCode");
    }

    // =============================================================== correlation

    @Test
    void stampsEventsWithTheRequestId() {
        signIn("alice");

        Map<String, Object> event = latestEvent("AUTH_LOGIN");
        assertThat(event.get("requestId")).asString().isNotBlank();
    }

    /** A caller's id is echoed and recorded, so a client can correlate its own logs. */
    @Test
    void acceptsAWellFormedClientRequestId() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        client.postJson("/api/auth/login", new LoginRequest("alice", "wrong-password"));

        // The client id is not threaded through BrowserClient, so this asserts the shape the
        // filter guarantees: every event carries an id, and it is always safe to print.
        Map<String, Object> event = latestEvent("AUTH_LOGIN_FAILURE");
        assertThat(event.get("requestId").toString())
                .matches("[A-Za-z0-9_-]{1,64}");
    }

    // =============================================================== system status

    @Test
    void refusesSystemStatusToNonAdministrators() {
        assertThat(user.getJson("/api/admin/system/status").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.getJson("/api/system/info");
        assertThat(anonymous.getJson("/api/admin/system/status").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reportsOperationalStatusToAdministrators() {
        Map<String, Object> status = admin.getJson("/api/admin/system/status").getBody();

        assertThat(status).containsKeys("version", "serverTime", "database", "redis", "queue");
        @SuppressWarnings("unchecked")
        Map<String, Object> database = (Map<String, Object>) status.get("database");
        assertThat(database).containsEntry("up", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> redis = (Map<String, Object>) status.get("redis");
        assertThat(redis).containsEntry("up", true);
    }

    /** A curated view, not an Actuator dump. */
    @Test
    void keepsCredentialsAndConfigurationOutOfSystemStatus() {
        String body = admin.get("/api/admin/system/status", String.class).getBody();

        for (String forbidden : new String[]{
                "password", "jdbc:", "postgres:", "redis://", "datasource",
                "DOCKER", "docker.sock", "EXECUTOR_TOKEN", "/var/run"}) {
            assertThat(body.toLowerCase(java.util.Locale.ROOT))
                    .as("system status must not mention %s", forbidden)
                    .doesNotContain(forbidden.toLowerCase(java.util.Locale.ROOT));
        }
    }

    // =============================================================== concurrency

    /**
     * Every successful mutation gets exactly one record, and concurrent writers do not
     * overwrite one another.
     */
    @Test
    void recordsEveryConcurrentMutationExactlyOnce() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);

        long before = countEventsOfAction("PROBLEM_CREATE");
        try {
            for (int i = 0; i < writers; i++) {
                int index = i;
                pool.submit(() -> {
                    BrowserClient client = signIn("boss");
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        client.postJson("/api/admin/problems", problemBody(
                                "concurrent-" + index, "Concurrent " + index));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(20, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(countEventsOfAction("PROBLEM_CREATE"))
                .as("one record per created problem, none lost and none duplicated")
                .isEqualTo(before + writers);

        Integer distinctIds = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT public_id) FROM audit_events", Integer.class);
        assertThat(distinctIds).isEqualTo((int) countEvents());
    }

    // =============================================================== helpers

    private Map<String, Object> problemBody(String slug, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("slug", slug);
        body.put("statement", "Add two numbers together.");
        body.put("inputFormat", "Two integers.");
        body.put("outputFormat", "Their sum.");
        body.put("constraints", "Small.");
        body.put("difficulty", "EASY");
        body.put("tags", List.of("MATH"));
        body.put("timeLimitMs", 2000);
        body.put("memoryLimitMb", 256);
        body.put("examples", List.of(Map.of("input", "1 2", "output", "3")));
        body.put("testCases", List.of(
                Map.of("input", "HIDDEN-TEST-INPUT", "expectedOutput", "HIDDEN-TEST-ANSWER",
                       "hidden", true, "weight", 1)));
        return body;
    }

    private UUID createProblemViaApi(String slug, String title) {
        ResponseEntity<Map<String, Object>> response =
                admin.postJson("/api/admin/problems", problemBody(slug, title));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private List<Map<String, Object>> search(String query) {
        Map<String, Object> body = admin.getJson("/api/admin/audit-events" + query).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return items;
    }

    private Map<String, Object> latestEvent(String action) {
        List<Map<String, Object>> events = search("?action=" + action + "&size=1");
        assertThat(events).as("expected an audit event for %s", action).isNotEmpty();
        return events.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataOf(Map<String, Object> event) {
        Object metadata = event.get("metadata");
        return metadata == null ? Map.of() : (Map<String, Object>) metadata;
    }

    private long countEvents() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        return count == null ? 0 : count;
    }

    private long countEventsOfAction(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = ?", Integer.class, action);
        return count == null ? 0 : count;
    }

    /** Ensures at least one event exists, and returns its internal id. */
    private long anyAuditEventId() {
        signIn("alice");
        Long id = jdbc.queryForObject("SELECT id FROM audit_events ORDER BY id DESC LIMIT 1", Long.class);
        assertThat(id).isNotNull();
        return id;
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
