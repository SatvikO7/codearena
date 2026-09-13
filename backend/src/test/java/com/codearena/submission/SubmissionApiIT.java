package com.codearena.submission;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemTag;
import com.codearena.queue.SubmissionQueue;
import com.codearena.queue.SubmissionRecoverySweeper;
import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.submission.dto.SubmissionRequest;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The submission pipeline, over real HTTP against real PostgreSQL and Redis.
 *
 * <p>No worker runs here, which is deliberate: these tests are about the API's half of the
 * contract — that a submission is persisted, queued, scoped to its owner, and never leaks
 * an answer key. The worker's half, including real Docker execution, is covered by the
 * worker module's own integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";
    private static final String HIDDEN_INPUT = "ZZ-HIDDEN-INPUT";
    private static final String HIDDEN_ANSWER = "ZZ-HIDDEN-ANSWER";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProblemRepository problemRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SubmissionRecoverySweeper sweeper;

    private BrowserClient author;
    private BrowserClient bystander;
    private UUID publishedProblemId;
    private UUID draftProblemId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM users");
        redis.delete(List.of(SubmissionQueue.PENDING, SubmissionQueue.PROCESSING));

        createAccount("ada", "ada@example.com");
        author = signIn("ada");
        createAccount("grace", "grace@example.com");
        bystander = signIn("grace");

        publishedProblemId = createProblem("Two Sum", "two-sum", true);
        draftProblemId = createProblem("Secret Draft", "secret-draft", false);
    }

    // ------------------------------------------------------------------ submitting

    @Test
    void acceptsASubmissionAndQueuesIt() {
        ResponseEntity<Map<String, Object>> response = submit(author, publishedProblemId, Language.PYTHON, "print(1)");

        assertThat(response.getStatusCode())
                .as("202: accepted for processing, the verdict does not exist yet")
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "QUEUED");
        assertThat(response.getBody().get("submissionId").toString()).hasSize(36);
    }

    /** The point of the whole architecture: the HTTP call does not wait for judging. */
    @Test
    void returnsWithoutExecutingAnything() {
        long startedAt = System.nanoTime();
        submit(author, publishedProblemId, Language.CPP, "int main(){}");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs)
                .as("the request must not block on compilation or execution")
                .isLessThan(2_000);
    }

    @Test
    void writesTheJobOntoTheRedisQueue() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");

        // The event listener fires after commit, so give it a moment to land.
        awaitQueueDepth(1);

        List<String> queued = redis.opsForList().range(SubmissionQueue.PENDING, 0, -1);
        assertThat(queued).containsExactly(submissionId.toString());
    }

    /** A small message, not a copy of the program: Redis must not become a second truth. */
    @Test
    void putsOnlyTheIdentifierOnTheQueue() {
        submit(author, publishedProblemId, Language.PYTHON, "print('SECRET-SOURCE-MARKER')");
        awaitQueueDepth(1);

        String job = redis.opsForList().index(SubmissionQueue.PENDING, 0);

        assertThat(job).doesNotContain("SECRET-SOURCE-MARKER");
        assertThat(UUID.fromString(job)).isNotNull();
    }

    @Test
    void recordsTheSubmissionAsQueuedAndPublished() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        awaitQueueDepth(1);

        Submission stored = submissionRepository.findByPublicId(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(stored.getEnqueuedAt()).as("publication marker must be set").isNotNull();
        assertThat(stored.getAttempts()).isZero();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void refusesAnUnsupportedLanguage() {
        ResponseEntity<Map<String, Object>> response = author.postJson(
                "/api/problems/" + publishedProblemId + "/submissions",
                Map.of("language", "BRAINFUCK", "sourceCode", "+++"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesEmptySource() {
        ResponseEntity<Map<String, Object>> response =
                submit(author, publishedProblemId, Language.PYTHON, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
    }

    @Test
    void refusesSourceLargerThanTheConfiguredLimit() {
        ResponseEntity<Map<String, Object>> response =
                submit(author, publishedProblemId, Language.PYTHON, "#".repeat(70_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("at most");
    }

    // ------------------------------------------------------------ problem visibility

    /** A draft must not be submittable, and must not confirm its own existence. */
    @Test
    void refusesToAcceptASubmissionForADraftProblem() {
        ResponseEntity<Map<String, Object>> response =
                submit(author, draftProblemId, Language.PYTHON, "print(1)");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "PROBLEM_NOT_FOUND");
    }

    @Test
    void refusesToAcceptASubmissionForAnArchivedProblem() {
        transactionTemplate.executeWithoutResult(status -> {
            Problem problem = problemRepository.findByPublicId(publishedProblemId).orElseThrow();
            User admin = userRepository.findByUsernameOrEmail("ada").orElseThrow();
            problem.archive(admin);
        });

        ResponseEntity<Map<String, Object>> response =
                submit(author, publishedProblemId, Language.PYTHON, "print(1)");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesASubmissionForAProblemThatDoesNotExist() {
        assertThat(submit(author, UUID.randomUUID(), Language.PYTHON, "print(1)").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void refusesAnonymousSubmissions() {
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.get("/api/system/info", Map.class);

        ResponseEntity<Map<String, Object>> response = anonymous.postJson(
                "/api/problems/" + publishedProblemId + "/submissions",
                Map.of("language", "PYTHON", "sourceCode", "print(1)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refusesSubmissionsWithoutACsrfToken() {
        author.forgetCsrfToken();

        ResponseEntity<Map<String, Object>> response = author.postJson(
                "/api/problems/" + publishedProblemId + "/submissions",
                Map.of("language", "PYTHON", "sourceCode", "print(1)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Somebody else's submission answers 404, so the endpoint is not an id oracle. */
    @Test
    void hidesOtherUsersSubmissions() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print('private')");

        ResponseEntity<Map<String, Object>> response =
                bystander.getJson("/api/submissions/" + submissionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().toString()).doesNotContain("private");
    }

    @Test
    void keepsEachUsersHistorySeparate() {
        submit(author, publishedProblemId, Language.PYTHON, "print('mine')");

        ResponseEntity<Map<String, Object>> theirs = bystander.getJson("/api/submissions");

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirs.getBody()).containsEntry("totalItems", 0);
        assertThat(theirs.getBody().toString()).doesNotContain("mine");
    }

    @Test
    void letsAnAdministratorInspectAnySubmission() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        createAccount("root", "root@example.com", Role.ADMIN);
        BrowserClient admin = signIn("root");

        assertThat(admin.getJson("/api/submissions/" + submissionId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ leakage

    /** The assertion this whole phase is judged on. */
    @Test
    void neverLeaksHiddenTestDataThroughAnySubmissionEndpoint() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");

        ResponseEntity<String> detail = author.get("/api/submissions/" + submissionId, String.class);
        ResponseEntity<String> list = author.get("/api/submissions", String.class);

        for (ResponseEntity<String> response : List.of(detail, list)) {
            assertThat(response.getBody())
                    .doesNotContain(HIDDEN_INPUT)
                    .doesNotContain(HIDDEN_ANSWER)
                    .doesNotContain("expectedOutput")
                    .doesNotContain("testCases");
        }
    }

    /** Source is the author's own, so the detail endpoint returns it; the list does not. */
    @Test
    void returnsSourceOnlyFromTheDetailEndpoint() {
        String source = "print('UNIQUE-SOURCE-MARKER')";
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, source);

        assertThat(author.get("/api/submissions/" + submissionId, String.class).getBody())
                .contains("UNIQUE-SOURCE-MARKER");
        assertThat(author.get("/api/submissions", String.class).getBody())
                .as("a history page has no use for kilobytes of program text")
                .doesNotContain("UNIQUE-SOURCE-MARKER");
    }

    // ------------------------------------------------------------------ the claim

    /**
     * Two workers, one submission, one winner.
     *
     * <p>This is the concurrency guarantee the pipeline rests on. Both threads run the same
     * atomic claim against the same row at the same moment; PostgreSQL's row lock must let
     * exactly one through. A read-then-update implementation would let both succeed, and two
     * containers would judge the same submission.
     */
    @Test
    void onlyOneWorkerCanEverClaimTheSameSubmission() throws Exception {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");

        int racers = 8;
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(racers);

        for (int i = 0; i < racers; i++) {
            String workerId = "racer-" + i;
            pool.submit(() -> {
                try {
                    startLine.await();
                    Integer claimed = jdbc.queryForObject("""
                            WITH claimed AS (
                                UPDATE submissions
                                SET status = 'RUNNING', claimed_by = ?, claimed_at = now(),
                                    attempts = attempts + 1, updated_at = now()
                                WHERE public_id = ? AND status = 'QUEUED'
                                RETURNING id
                            )
                            SELECT count(*) FROM claimed
                            """, Integer.class, workerId, submissionId);
                    if (claimed != null && claimed == 1) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    // A serialisation failure is also "did not claim", which is correct.
                } finally {
                    startLine.countDown();
                }
            });
        }

        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get())
                .as("exactly one of %d concurrent workers may claim a submission", racers)
                .isEqualTo(1);

        Submission stored = submissionRepository.findByPublicId(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.RUNNING);
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    /** A duplicate delivery must find nothing to do rather than judging twice. */
    @Test
    void aSecondDeliveryOfTheSameJobClaimsNothing() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");

        int first = claimOnce(submissionId, "worker-a");
        int second = claimOnce(submissionId, "worker-b");

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the redelivery must be a no-op").isZero();
    }

    /** A worker whose lease expired must not overwrite a newer verdict. */
    @Test
    void aStaleWorkerCannotOverwriteARecordedResult() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        claimOnce(submissionId, "worker-a");

        int recorded = jdbc.update("""
                UPDATE submissions SET status = 'ACCEPTED', finished_at = now()
                WHERE public_id = ? AND status = 'RUNNING' AND claimed_by = 'worker-a'
                """, submissionId);
        assertThat(recorded).isEqualTo(1);

        int stale = jdbc.update("""
                UPDATE submissions SET status = 'WRONG_ANSWER'
                WHERE public_id = ? AND status = 'RUNNING' AND claimed_by = 'worker-b'
                """, submissionId);

        assertThat(stale).as("a straggler writes nothing").isZero();
        assertThat(submissionRepository.findByPublicId(submissionId).orElseThrow().getStatus())
                .isEqualTo(SubmissionStatus.ACCEPTED);
    }

    // ------------------------------------------------------------------ recovery

    /**
     * The outbox guarantee: a submission committed but never published still gets judged.
     *
     * <p>Simulated by clearing the publication marker and emptying Redis, which is exactly
     * the state the API server dying between commit and push would leave behind.
     */
    @Test
    void republishesASubmissionThatNeverReachedTheQueue() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        awaitQueueDepth(1);

        redis.delete(SubmissionQueue.PENDING);
        jdbc.update("UPDATE submissions SET enqueued_at = NULL WHERE public_id = ?", submissionId);

        int republished = sweeper.republishUnpublished();

        assertThat(republished).isEqualTo(1);
        assertThat(redis.opsForList().range(SubmissionQueue.PENDING, 0, -1))
                .containsExactly(submissionId.toString());
        assertThat(submissionRepository.findByPublicId(submissionId).orElseThrow().getEnqueuedAt())
                .isNotNull();
    }

    /** A worker that died holding a claim must not strand its submission in RUNNING. */
    @Test
    void returnsASubmissionToTheQueueWhenItsWorkerDies() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        claimOnce(submissionId, "worker-that-died");
        expireTheClaim(submissionId);

        int recovered = sweeper.recoverExpiredClaims();

        assertThat(recovered).isEqualTo(1);
        Submission stored = submissionRepository.findByPublicId(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(stored.getClaimedBy()).isNull();
        assertThat(stored.getEnqueuedAt()).as("cleared so the sweeper republishes it").isNull();
    }

    /**
     * Retries are bounded. A submission that reliably kills whichever worker takes it must
     * not cycle through the pool for ever.
     */
    @Test
    void givesUpWithSystemErrorOnceAttemptsAreExhausted() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        claimOnce(submissionId, "doomed-worker");
        jdbc.update("UPDATE submissions SET attempts = 3 WHERE public_id = ?", submissionId);
        expireTheClaim(submissionId);

        sweeper.recoverExpiredClaims();

        Submission stored = submissionRepository.findByPublicId(submissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(stored.getFinishedAt()).isNotNull();
        assertThat(stored.getErrorMessage()).contains("could not complete");
    }

    @Test
    void leavesHealthySubmissionsAlone() {
        submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        awaitQueueDepth(1);

        assertThat(sweeper.republishUnpublished()).isZero();
        assertThat(sweeper.recoverExpiredClaims()).isZero();
    }

    // ------------------------------------------------------------------ listing

    @Test
    void listsOwnSubmissionsNewestFirst() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");
        submit(author, publishedProblemId, Language.CPP, "int main(){}");
        submit(author, publishedProblemId, Language.JAVA, "public class Main{public static void main(String[] a){}}");

        ResponseEntity<Map<String, Object>> response = author.getJson("/api/submissions");

        assertThat(response.getBody()).containsEntry("totalItems", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).extracting(item -> item.get("language"))
                .containsExactly("JAVA", "CPP", "PYTHON");
    }

    @Test
    void filtersHistoryByProblemAndStatus() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");

        assertThat(author.getJson("/api/submissions?status=QUEUED").getBody())
                .containsEntry("totalItems", 1);
        assertThat(author.getJson("/api/submissions?status=ACCEPTED").getBody())
                .containsEntry("totalItems", 0);
        assertThat(author.getJson("/api/submissions?problemId=" + UUID.randomUUID()).getBody())
                .as("an unknown problem filter must match nothing, not everything")
                .containsEntry("totalItems", 0);
    }

    // ------------------------------------------------------- history and filters

    @Test
    void filtersHistoryByLanguage() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");
        submit(author, publishedProblemId, Language.CPP, "int main(){}");
        submit(author, publishedProblemId, Language.CPP, "int main(){return 0;}");

        assertThat(author.getJson("/api/submissions?language=CPP").getBody())
                .containsEntry("totalItems", 2);
        assertThat(author.getJson("/api/submissions?language=PYTHON").getBody())
                .containsEntry("totalItems", 1);
        assertThat(author.getJson("/api/submissions?language=JAVA").getBody())
                .containsEntry("totalItems", 0);
    }

    @Test
    void combinesFilters() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");
        submit(author, publishedProblemId, Language.CPP, "int main(){}");

        assertThat(author.getJson(
                "/api/submissions?language=CPP&status=QUEUED&problemId=" + publishedProblemId).getBody())
                .containsEntry("totalItems", 1);
        assertThat(author.getJson(
                "/api/submissions?language=CPP&status=ACCEPTED").getBody())
                .as("a filter combination matching nothing must return nothing, not everything")
                .containsEntry("totalItems", 0);
    }

    @Test
    void reportsAnUnknownFilterValueAsABadRequest() {
        assertThat(author.getJson("/api/submissions?language=COBOL").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(author.getJson("/api/submissions?status=MAYBE").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void paginatesHistoryDeterministically() {
        for (int i = 0; i < 5; i++) {
            submit(author, publishedProblemId, Language.PYTHON, "print(" + i + ")");
        }

        ResponseEntity<Map<String, Object>> first = author.getJson("/api/submissions?page=0&size=2");
        ResponseEntity<Map<String, Object>> second = author.getJson("/api/submissions?page=1&size=2");

        assertThat(first.getBody()).containsEntry("totalItems", 5).containsEntry("totalPages", 3)
                .containsEntry("hasNext", true).containsEntry("hasPrevious", false);
        // No id may appear on two pages, which is what a unique tiebreaker guarantees.
        assertThat(idsOf(first)).doesNotContainAnyElementsOf(idsOf(second));
    }

    @Test
    void clampsAnOversizedHistoryPage() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");

        assertThat(author.getJson("/api/submissions?size=99999").getBody())
                .containsEntry("size", 100);
    }

    // ------------------------------------------------ per-problem history endpoint

    @Test
    void listsSubmissionsForOneProblem() {
        submit(author, publishedProblemId, Language.PYTHON, "print(1)");

        ResponseEntity<Map<String, Object>> response =
                author.getJson("/api/problems/" + publishedProblemId + "/submissions");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalItems", 1);
    }

    /**
     * This endpoint is addressed by problem, so it is the most tempting one to mistake for a
     * public feed of everybody's attempts. It is not: it returns the caller's own rows only.
     */
    @Test
    void theProblemScopedHistoryIsNotAPublicFeed() {
        submit(author, publishedProblemId, Language.PYTHON, "print('ada-only')");

        ResponseEntity<String> theirs = bystander.get(
                "/api/problems/" + publishedProblemId + "/submissions", String.class);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirs.getBody()).contains("\"totalItems\":0");
        assertThat(theirs.getBody()).doesNotContain("ada-only");
    }

    @Test
    void theProblemScopedHistoryNeverReturnsSourceCode() {
        submit(author, publishedProblemId, Language.PYTHON, "print('UNIQUE-LIST-SOURCE')");

        assertThat(author.get("/api/problems/" + publishedProblemId + "/submissions", String.class).getBody())
                .doesNotContain("UNIQUE-LIST-SOURCE")
                .doesNotContain("sourceCode");
    }

    // ------------------------------------------------------------ test results

    /**
     * The detail response exposes which tests failed and nothing about them. This asserts
     * the whole body, so a future field carrying test content fails here.
     */
    @Test
    void exposesPerTestOutcomesWithoutTheTestContents() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");
        Submission stored = submissionRepository.findByPublicId(submissionId).orElseThrow();

        jdbc.update("UPDATE submissions SET status = 'WRONG_ANSWER', tests_total = 2, tests_passed = 1, "
                + "failed_test_index = 1, runtime_ms = 12, finished_at = now() WHERE public_id = ?", submissionId);
        jdbc.update("INSERT INTO submission_test_results (submission_id, position, passed, runtime_ms, hidden) "
                + "VALUES (?, 0, true, 11, false), (?, 1, false, 12, true)", stored.getId(), stored.getId());

        ResponseEntity<String> raw = author.get("/api/submissions/" + submissionId, String.class);

        assertThat(raw.getBody())
                .contains("testResults")
                .contains("\"position\":0")
                .contains("\"passed\":true")
                .contains("\"hidden\":true")
                .doesNotContain(HIDDEN_INPUT)
                .doesNotContain(HIDDEN_ANSWER)
                .doesNotContain("expectedOutput");
    }

    @Test
    void aSubmissionWithNoResultsYetReportsAnEmptyTestList() {
        UUID submissionId = submitAndExtractId(author, publishedProblemId, Language.PYTHON, "print(1)");

        ResponseEntity<Map<String, Object>> response = author.getJson("/api/submissions/" + submissionId);

        assertThat((List<?>) response.getBody().get("testResults")).isEmpty();
        assertThat(response.getBody()).containsEntry("attempts", 0);
    }

    // ------------------------------------------------------------------ helpers

    private List<String> idsOf(ResponseEntity<Map<String, Object>> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        return items.stream().map(item -> item.get("id").toString()).toList();
    }

    private ResponseEntity<Map<String, Object>> submit(BrowserClient client, UUID problemId,
                                                       Language language, String source) {
        return client.postJson("/api/problems/" + problemId + "/submissions",
                new SubmissionRequest(language, source));
    }

    private UUID submitAndExtractId(BrowserClient client, UUID problemId, Language language, String source) {
        ResponseEntity<Map<String, Object>> response = submit(client, problemId, language, source);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(response.getBody().get("submissionId").toString());
    }

    private int claimOnce(UUID submissionId, String workerId) {
        return jdbc.update("""
                UPDATE submissions
                SET status = 'RUNNING', claimed_by = ?, claimed_at = now(),
                    started_at = now(), attempts = attempts + 1, updated_at = now()
                WHERE public_id = ? AND status = 'QUEUED'
                """, workerId, submissionId);
    }

    private void expireTheClaim(UUID submissionId) {
        jdbc.update("UPDATE submissions SET claimed_at = now() - interval '1 hour' WHERE public_id = ?",
                submissionId);
    }

    /** The publication listener fires after commit, so the push is briefly asynchronous. */
    private void awaitQueueDepth(long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Long size = redis.opsForList().size(SubmissionQueue.PENDING);
            if (size != null && size >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("queue never reached depth " + expected);
    }

    private void createAccount(String username, String email) {
        createAccount(username, email, Role.USER);
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private BrowserClient signIn(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.get("/api/system/info", Map.class);
        assertThat(client.postJson("/api/auth/login", new LoginRequest(username, PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return client;
    }

    /** Creates a problem with one hidden test case whose contents must never escape. */
    private UUID createProblem(String title, String slug, boolean publish) {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.findByUsernameOrEmail("ada").orElseThrow();
            Problem problem = Problem.createDraft(slug, title, Difficulty.EASY, owner);
            problem.updateContent(title, "Add two numbers.", "Two integers.", "Their sum.",
                    "1 <= a, b <= 100", null, Difficulty.EASY, 2000, 256,
                    Set.of(ProblemTag.ARRAY), owner);
            problem.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", null)));
            problem.replaceTestCases(List.of(
                    new Problem.TestCaseContent(HIDDEN_INPUT, HIDDEN_ANSWER, true, 1)));
            if (publish) {
                problem.publish(owner);
            }
            return problemRepository.saveAndFlush(problem).getPublicId();
        });
    }
}
