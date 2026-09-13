package com.codearena.events;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.shared.Language;
import com.codearena.shared.SubmissionEvent;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.dto.SubmissionRequest;
import com.codearena.support.BrowserClient;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSE endpoint, over a real HTTP connection.
 *
 * <p>These tests open an actual {@code text/event-stream} and read raw frames off the socket
 * rather than calling the controller method. That distinction matters: the questions here
 * are whether the stream is genuinely authorised, whether it really closes when a verdict
 * lands, and whether the bytes on the wire are the documented format. A mocked emitter would
 * answer none of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionStreamIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";
    private static final String HIDDEN_INPUT = "ZZ-STREAM-HIDDEN-INPUT";
    private static final String HIDDEN_ANSWER = "ZZ-STREAM-HIDDEN-ANSWER";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProblemRepository problemRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SubmissionStreamRegistry registry;

    private BrowserClient author;
    private BrowserClient bystander;
    private UUID problemId;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);

        createAccount("ada", "ada@example.com", Role.USER);
        author = signIn("ada");
        createAccount("grace", "grace@example.com", Role.USER);
        bystander = signIn("grace");

        problemId = createPublishedProblem();
    }

    // ------------------------------------------------------------- authorization

    /** The stream must not be a weaker door to data the REST endpoint protects. */
    @Test
    @Timeout(60)
    void refusesToStreamAnotherUsersSubmission() throws Exception {
        UUID submissionId = submit(author);

        int status = openStreamStatus(submissionId, bystander);

        assertThat(status)
                .as("404, not 403: telling a stranger the submission exists would leak it")
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @Timeout(60)
    void refusesToStreamWithoutASession() throws Exception {
        UUID submissionId = submit(author);

        HttpURLConnection connection = connect(submissionId, null);

        assertThat(connection.getResponseCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        connection.disconnect();
    }

    @Test
    @Timeout(60)
    void refusesToStreamASubmissionThatDoesNotExist() throws Exception {
        assertThat(openStreamStatus(UUID.randomUUID(), author))
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @Timeout(60)
    void letsAnAdministratorWatchAnySubmission() throws Exception {
        UUID submissionId = submit(author);
        createAccount("root", "root@example.com", Role.ADMIN);
        BrowserClient admin = signIn("root");

        assertThat(openStreamStatus(submissionId, admin)).isEqualTo(HttpStatus.OK.value());
    }

    // ---------------------------------------------------------------- the stream

    /**
     * The snapshot is what makes reconnects converge: a client that missed every event while
     * it was away still learns the truth the instant it returns.
     */
    @Test
    @Timeout(60)
    void sendsTheCurrentStateImmediatelyOnConnect() throws Exception {
        UUID submissionId = submit(author);

        List<String> frames = readFrames(submissionId, author, 1, 15);

        String payload = String.join("\n", frames);
        assertThat(payload).contains("event:snapshot");
        assertThat(payload).contains("\"status\":\"QUEUED\"");
        assertThat(payload).contains("\"terminal\":false");
        assertThat(payload).contains("\"updatedAt\"");
    }

    /** A state change published by a worker must reach an open stream. */
    @Test
    @Timeout(60)
    void deliversAStateChangeToAnOpenStream() throws Exception {
        UUID submissionId = submit(author);

        CompletableFuture<List<String>> frames = CompletableFuture.supplyAsync(() -> {
            try {
                return readFrames(submissionId, author, 2, 20);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        awaitWatcher(submissionId);
        // Exactly what the worker does: move the row, then announce it.
        jdbc.update("UPDATE submissions SET status = 'RUNNING', claimed_by = 'w1', "
                + "claimed_at = now(), started_at = now(), updated_at = now() WHERE public_id = ?", submissionId);
        publish(submissionId, SubmissionStatus.RUNNING);

        String payload = String.join("\n", frames.get(30, TimeUnit.SECONDS));
        assertThat(payload).contains("event:submission");
        assertThat(payload).contains("\"status\":\"RUNNING\"");
    }

    /**
     * Nothing further can happen to a judged submission, so the server closes the stream
     * rather than pinning a thread on an event that cannot come.
     */
    @Test
    @Timeout(60)
    void closesTheStreamOnceTheVerdictIsFinal() throws Exception {
        UUID submissionId = submit(author);

        CompletableFuture<List<String>> frames = CompletableFuture.supplyAsync(() -> {
            try {
                // Reads until the server closes, not until a frame count is reached.
                return readUntilClosed(submissionId, author, 25);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        awaitWatcher(submissionId);
        markTerminal(submissionId, SubmissionStatus.ACCEPTED);
        publish(submissionId, SubmissionStatus.ACCEPTED);

        String payload = String.join("\n", frames.get(30, TimeUnit.SECONDS));
        assertThat(payload).contains("\"status\":\"ACCEPTED\"").contains("\"terminal\":true");

        // The registry must have released it, or the connection cap would leak.
        assertThat(registry.hasWatchers(submissionId)).isFalse();
    }

    /** A submission that is already judged needs no connection held open at all. */
    @Test
    @Timeout(60)
    void answersAnAlreadyJudgedSubmissionWithASnapshotAndCloses() throws Exception {
        UUID submissionId = submit(author);
        markTerminal(submissionId, SubmissionStatus.WRONG_ANSWER);

        List<String> frames = readUntilClosed(submissionId, author, 20);

        String payload = String.join("\n", frames);
        assertThat(payload).contains("event:snapshot").contains("\"status\":\"WRONG_ANSWER\"");
        assertThat(registry.hasWatchers(submissionId))
                .as("a terminal submission must not hold a connection")
                .isFalse();
    }

    // ------------------------------------------------------------------ leakage

    /** The stream is another response body, and the same rule applies to it. */
    @Test
    @Timeout(60)
    void neverStreamsHiddenTestData() throws Exception {
        UUID submissionId = submit(author);
        markTerminal(submissionId, SubmissionStatus.WRONG_ANSWER);

        String payload = String.join("\n", readUntilClosed(submissionId, author, 20));

        assertThat(payload)
                .doesNotContain(HIDDEN_INPUT)
                .doesNotContain(HIDDEN_ANSWER)
                .doesNotContain("expectedOutput");
    }

    /** A status frame is watched by a page that already has the code; re-sending it is waste. */
    @Test
    @Timeout(60)
    void neverStreamsSourceCode() throws Exception {
        UUID submissionId = submitWithSource(author, "print('UNIQUE-STREAM-SOURCE')");
        markTerminal(submissionId, SubmissionStatus.ACCEPTED);

        String payload = String.join("\n", readUntilClosed(submissionId, author, 20));

        assertThat(payload).doesNotContain("UNIQUE-STREAM-SOURCE").doesNotContain("sourceCode");
    }

    // --------------------------------------------------------------- robustness

    /** A malformed message must not take the subscriber, or the stream, down with it. */
    @Test
    @Timeout(60)
    void survivesAnUnreadableEvent() throws Exception {
        UUID submissionId = submit(author);

        CompletableFuture<List<String>> frames = CompletableFuture.supplyAsync(() -> {
            try {
                return readFrames(submissionId, author, 2, 25);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        awaitWatcher(submissionId);
        redis.convertAndSend(SubmissionEvent.CHANNEL, "{ this is not valid json");
        Thread.sleep(300);

        // A good event after the bad one still arrives.
        markTerminal(submissionId, SubmissionStatus.ACCEPTED);
        publish(submissionId, SubmissionStatus.ACCEPTED);

        assertThat(String.join("\n", frames.get(30, TimeUnit.SECONDS))).contains("\"status\":\"ACCEPTED\"");
    }

    /**
     * Duplicate delivery is expected under at-least-once and must be harmless. The client
     * converges by discarding anything not newer than what it holds; the server's job is
     * simply not to break.
     */
    @Test
    @Timeout(60)
    void toleratesDuplicateEvents() throws Exception {
        UUID submissionId = submit(author);

        CompletableFuture<List<String>> frames = CompletableFuture.supplyAsync(() -> {
            try {
                return readUntilClosed(submissionId, author, 25);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        awaitWatcher(submissionId);
        markTerminal(submissionId, SubmissionStatus.ACCEPTED);
        for (int i = 0; i < 5; i++) {
            publish(submissionId, SubmissionStatus.ACCEPTED);
        }

        String payload = String.join("\n", frames.get(30, TimeUnit.SECONDS));
        assertThat(payload).contains("\"status\":\"ACCEPTED\"");
        assertThat(registry.hasWatchers(submissionId)).isFalse();
    }

    // -------------------------------------------------------------------- helpers

    private void publish(UUID submissionId, SubmissionStatus status) throws Exception {
        redis.convertAndSend(SubmissionEvent.CHANNEL, objectMapper.writeValueAsString(
                new SubmissionEvent(submissionId, status, Instant.now())));
    }

    private void markTerminal(UUID submissionId, SubmissionStatus status) {
        jdbc.update("UPDATE submissions SET status = ?, tests_total = 2, tests_passed = ?, "
                        + "runtime_ms = 12, finished_at = now(), updated_at = now() WHERE public_id = ?",
                status.name(), status == SubmissionStatus.ACCEPTED ? 2 : 1, submissionId);
    }

    /** Waits for the server to register the stream before publishing into it. */
    private void awaitWatcher(UUID submissionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (registry.hasWatchers(submissionId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the stream never registered a watcher");
    }

    private int openStreamStatus(UUID submissionId, BrowserClient client) throws Exception {
        HttpURLConnection connection = connect(submissionId, client);
        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
    }

    /** Reads at least {@code minFrames} non-blank lines, or gives up. */
    private List<String> readFrames(UUID submissionId, BrowserClient client,
                                    int minFrames, int timeoutSeconds) throws Exception {
        HttpURLConnection connection = connect(submissionId, client);
        assertThat(connection.getResponseCode()).isEqualTo(HttpStatus.OK.value());

        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
                if (countEvents(lines) >= minFrames) {
                    break;
                }
            }
        } finally {
            connection.disconnect();
        }
        return lines;
    }

    /** Reads until the server closes the stream, which is the behaviour under test. */
    private List<String> readUntilClosed(UUID submissionId, BrowserClient client,
                                         int timeoutSeconds) throws Exception {
        HttpURLConnection connection = connect(submissionId, client);
        assertThat(connection.getResponseCode()).isEqualTo(HttpStatus.OK.value());

        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } finally {
            connection.disconnect();
        }
        return lines;
    }

    /**
     * Counts <em>complete</em> frames, by their data line.
     *
     * <p>Counting {@code event:} lines instead would stop reading the moment the event name
     * arrived and before its payload did, so every assertion about the body would see only
     * the header.
     */
    private long countEvents(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("data:")).count();
    }

    private HttpURLConnection connect(UUID submissionId, BrowserClient client) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/submissions/" + submissionId + "/events");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestProperty("Accept", "text/event-stream");
        if (client != null) {
            connection.setRequestProperty("Cookie", client.cookieHeader());
        }
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    private UUID submit(BrowserClient client) {
        return submitWithSource(client, "print(1)");
    }

    private UUID submitWithSource(BrowserClient client, String source) {
        ResponseEntity<Map<String, Object>> response = client.postJson(
                "/api/problems/" + problemId + "/submissions",
                new SubmissionRequest(Language.PYTHON, source));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(response.getBody().get("submissionId").toString());
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private BrowserClient signIn(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        assertThat(client.postJson("/api/auth/login", new LoginRequest(username, PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return client;
    }

    private UUID createPublishedProblem() {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.findByUsernameOrEmail("ada").orElseThrow();
            Problem problem = Problem.createDraft("stream-problem", "Stream Problem", Difficulty.EASY, owner);
            problem.updateContent("Stream Problem", "Add them.", "Two integers.", "The sum.",
                    "1 <= a, b <= 10", null, Difficulty.EASY, 2000, 256, Set.of(), owner);
            problem.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", null)));
            problem.replaceTestCases(List.of(
                    new Problem.TestCaseContent(HIDDEN_INPUT, HIDDEN_ANSWER, true, 1)));
            problem.publish(owner);
            return problemRepository.saveAndFlush(problem).getPublicId();
        });
    }
}
