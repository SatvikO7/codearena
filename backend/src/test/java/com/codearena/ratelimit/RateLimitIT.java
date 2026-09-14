package com.codearena.ratelimit;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemTag;
import com.codearena.queue.SubmissionQueue;
import com.codearena.shared.Language;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
 * Rate limiting over real HTTP, against real Redis.
 *
 * <p>The limits here are deliberately tiny — three or four requests — so that reaching one
 * takes a moment rather than a minute. What is being tested is the mechanism, not the
 * shipped numbers: that the count is enforced atomically, that it is enforced against the
 * right identity, that it cannot be escaped by changing how the request is spelled, and
 * that authorisation still decides before it does. The production values are configuration
 * and are asserted where they belong, in {@code RateLimitPropertiesTest} and in the
 * end-to-end script.
 *
 * <p>Real Redis rather than a mock, for the same reason the rest of this project uses real
 * infrastructure: the property under test is atomicity under concurrency, which a mock
 * cannot fail to provide and therefore cannot demonstrate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Small enough to reach quickly; long enough to refill that a test never races it.
        "codearena.rate-limit.login-origin.capacity=6",
        "codearena.rate-limit.login-origin.refill-interval=60s",
        "codearena.rate-limit.login-account.capacity=3",
        "codearena.rate-limit.login-account.refill-interval=60s",
        "codearena.rate-limit.registration.capacity=3",
        "codearena.rate-limit.registration.refill-interval=60s",
        "codearena.rate-limit.submission.capacity=3",
        "codearena.rate-limit.submission.refill-interval=60s",
        "codearena.rate-limit.standings.capacity=3",
        "codearena.rate-limit.standings.refill-interval=60s",
        "codearena.rate-limit.problem-search.capacity=3",
        "codearena.rate-limit.problem-search.refill-interval=60s",
        "codearena.rate-limit.admin-read.capacity=4",
        "codearena.rate-limit.admin-read.refill-interval=60s"
})
class RateLimitIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProblemRepository problemRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RateLimitService rateLimitService;

    private BrowserClient admin;
    private BrowserClient alice;
    private BrowserClient bob;
    private UUID problemId;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        redis.delete(List.of(SubmissionQueue.PENDING, SubmissionQueue.PROCESSING));

        createAccount("boss", "boss@example.com", Role.ADMIN);
        createAccount("alice", "alice@example.com", Role.USER);
        createAccount("bob", "bob@example.com", Role.USER);

        admin = signIn("boss");
        alice = signIn("alice");
        bob = signIn("bob");

        problemId = createPublishedProblem();

        // The sign-ins above spent login allowance that these tests are not about.
        clearBuckets();
    }

    // =============================================================== authentication

    /** The control must not obstruct the thing it is protecting. */
    @Test
    void letsSomebodyLogInNormally() {
        assertThat(attemptLogin("alice", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Password guessing runs out of allowance. The account bucket is the binding one here:
     * three failures, then refusal.
     */
    @Test
    void throttlesRepeatedPasswordGuessing() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(attemptLogin("alice", "wrong").getStatusCode())
                    .as("attempt %d should be answered normally", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map<String, Object>> refused = attemptLogin("alice", "wrong");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody()).containsEntry("error", "RATE_LIMITED");
    }

    /**
     * The throttle is a delay, never a lock. An attacker who exhausts an account's bucket
     * must not have taken the account away from its owner — which is what a lockout would
     * do, and why this system does not have one.
     */
    @Test
    void doesNotLetAnAttackerLockSomebodyOutOfTheirOwnAccount() {
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("alice", "wrong");
        }

        // The bucket refills on its own; a test cannot wait a minute for it, so this asserts
        // the property that makes recovery possible -- the state is a bucket with a TTL and
        // not a flag on the user.
        assertThat(userIsStillEnabled("alice"))
                .as("throttling must never touch the account itself")
                .isTrue();
        assertThat(redis.getExpire(RateLimitService.keyFor(
                RateLimitPolicy.LOGIN_ACCOUNT, CallerIdentity.ofAccount("alice"))))
                .as("the throttle expires by itself")
                .isGreaterThan(0);

        rateLimitService.reset(RateLimitPolicy.LOGIN_ACCOUNT, CallerIdentity.ofAccount("alice"));
        assertThat(attemptLogin("alice", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A correct password clears the record of failures, so a legitimate user working from a
     * forgotten password does not accumulate against themselves.
     */
    @Test
    void forgivesFailedAttemptsOnceTheRealPasswordArrives() {
        attemptLogin("alice", "wrong");
        attemptLogin("alice", "wrong");

        assertThat(attemptLogin("alice", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(redis.hasKey(RateLimitService.keyFor(
                RateLimitPolicy.LOGIN_ACCOUNT, CallerIdentity.ofAccount("alice"))))
                .as("a successful login empties the failed-attempt record")
                .isFalse();
    }

    /**
     * Guessing at one account must not consume another account's allowance, or an attacker
     * could deny service to every user by attacking one.
     */
    @Test
    void keepsOneAccountsThrottleAwayFromAnothers() {
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("alice", "wrong");
        }

        assertThat(attemptLogin("bob", PASSWORD).getStatusCode())
                .as("bob is unaffected by an attack on alice")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Spelling the username differently must not buy a fresh allowance. Without
     * normalisation, "limit per account" would be "limit per capitalisation".
     */
    @Test
    void cannotEscapeTheAccountThrottleByChangingTheCapitalisation() {
        attemptLogin("alice", "wrong");
        attemptLogin("ALICE", "wrong");
        attemptLogin("  Alice  ", "wrong");

        assertThat(attemptLogin("aLiCe", "wrong").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * A throttled login must look exactly like a throttled registration, or a 429 becomes
     * an oracle telling an attacker which accounts are worth attacking.
     */
    @Test
    void refusesARealAndAnImaginaryAccountIdentically() {
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("alice", "wrong");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("nobody-at-all", "wrong");
        }

        ResponseEntity<Map<String, Object>> real = attemptLogin("alice", "wrong");
        ResponseEntity<Map<String, Object>> imaginary = attemptLogin("nobody-at-all", "wrong");

        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(imaginary.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(real.getBody().get("message")).isEqualTo(imaginary.getBody().get("message"));
        assertThat(real.getBody().get("error")).isEqualTo(imaginary.getBody().get("error"));
    }

    @Test
    void limitsAccountCreation() {
        for (int i = 0; i < 3; i++) {
            assertThat(register("newcomer" + i).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        assertThat(register("newcomer-too-many").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Rate limiting is an extra defence, never a replacement for the constraints that make
     * the data correct. A duplicate username is still a 409 while allowance remains.
     */
    @Test
    void doesNotReplaceTheUniquenessConstraints() {
        assertThat(register("alice").getStatusCode())
                .as("the unique username constraint still decides")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // =============================================================== submissions

    @Test
    void limitsSubmissionsPerUser() {
        for (int i = 0; i < 3; i++) {
            assertThat(submit(alice).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<Map<String, Object>> refused = submit(alice);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody()).containsEntry("error", "RATE_LIMITED");
    }

    /** The limit is applied before the work is queued, which is the entire point. */
    @Test
    void refusesTheSubmissionBeforeAnythingIsQueuedOrWritten() {
        for (int i = 0; i < 3; i++) {
            submit(alice);
        }
        long queuedBefore = queueDepth();
        long storedBefore = submissionCount();

        assertThat(submit(alice).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(queueDepth())
                .as("a refused submission must not reach the judge queue")
                .isEqualTo(queuedBefore);
        assertThat(submissionCount())
                .as("a refused submission must not become a row")
                .isEqualTo(storedBefore);
    }

    @Test
    void keepsOneUsersSubmissionAllowanceAwayFromAnothers() {
        for (int i = 0; i < 4; i++) {
            submit(alice);
        }

        assertThat(submit(bob).getStatusCode())
                .as("bob's allowance is his own")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    /**
     * The contest endpoint shares the practice bucket deliberately. If it did not, being
     * refused on one would simply mean submitting through the other — a bypass built into
     * the design rather than left in it by accident.
     */
    @Test
    void cannotSubmitThroughTheContestEndpointToEscapeThePracticeLimit() {
        UUID contestId = liveContestWith(alice);
        clearBuckets();

        for (int i = 0; i < 3; i++) {
            assertThat(submit(alice).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        assertThat(submitToContest(alice, contestId).getStatusCode())
                .as("practice and contest submissions share one allowance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** And the same in the other direction, so neither endpoint is the loophole. */
    @Test
    void cannotSubmitThroughThePracticeEndpointToEscapeTheContestLimit() {
        UUID contestId = liveContestWith(alice);
        clearBuckets();

        for (int i = 0; i < 3; i++) {
            assertThat(submitToContest(alice, contestId).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }

        assertThat(submit(alice).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * A contest submission that is refused was never accepted, so it cannot appear in the
     * standings. Scoring is untouched by this phase and this asserts it.
     */
    @Test
    void aThrottledContestSubmissionIsNeverScored() {
        UUID contestId = liveContestWith(alice);
        clearBuckets();

        for (int i = 0; i < 3; i++) {
            submitToContest(alice, contestId);
        }
        long accepted = submissionCount();

        assertThat(submitToContest(alice, contestId).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(submissionCount())
                .as("a refused contest submission is not a submission")
                .isEqualTo(accepted);
    }

    // =============================================================== atomicity

    /**
     * The property that separates a rate limiter from a suggestion.
     *
     * <p>Twenty threads submit at the same instant against a capacity of three. A
     * read-then-write implementation would let most of them through, because they would all
     * read the same remaining count before any of them wrote. Exactly three may pass.
     */
    @Test
    void admitsExactlyTheConfiguredNumberUnderConcurrency() throws Exception {
        int threads = 20;
        int capacity = 3;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        HttpStatus status = (HttpStatus) submit(alice).getStatusCode();
                        if (status == HttpStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                            refused.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(accepted.get())
                .as("the limit is the limit, however many callers arrive at once")
                .isEqualTo(capacity);
        assertThat(accepted.get() + refused.get()).isEqualTo(threads);
    }

    // =============================================================== ordering and authz

    /**
     * Rate limiting does not replace authorisation, and must not run before it: an
     * anonymous caller has to be told to authenticate, not to slow down.
     */
    @Test
    void answers401BeforeItEverConsidersALimit() {
        BrowserClient stranger = new BrowserClient(restTemplate);
        stranger.getJson("/api/system/info");

        for (int i = 0; i < 6; i++) {
            assertThat(stranger.postJson("/api/problems/" + problemId + "/submissions",
                    new SubmissionRequest(Language.PYTHON, "print(1)")).getStatusCode())
                    .as("never 429: an unauthenticated caller is refused by authentication")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /** And a caller who is authenticated but not permitted gets 403, not 429. */
    @Test
    void answers403BeforeItEverConsidersALimit() {
        for (int i = 0; i < 8; i++) {
            assertThat(alice.getJson("/api/admin/audit-events").getStatusCode())
                    .as("authorisation still decides first")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** Administrators are limited too: being trusted is not an exemption. */
    @Test
    void limitsAdministratorsAsWell() {
        for (int i = 0; i < 4; i++) {
            assertThat(admin.getJson("/api/admin/audit-events").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(admin.getJson("/api/admin/audit-events").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // =============================================================== bypass attempts

    /**
     * The limit follows the handler, not a path pattern, so the spellings that defeat a
     * pattern-matched rule do nothing here.
     */
    @Test
    void cannotBeEscapedBySpellingThePathDifferently() {
        for (int i = 0; i < 3; i++) {
            submit(alice);
        }

        assertThat(alice.postJson("/api/problems/" + problemId + "/submissions/",
                new SubmissionRequest(Language.PYTHON, "print(1)")).getStatusCode())
                .as("a trailing slash is not a different endpoint")
                .isIn(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.NOT_FOUND);
    }

    /**
     * The identity comes from the session, so naming somebody else in the request changes
     * nothing at all.
     */
    @Test
    void cannotSpendSomebodyElsesAllowanceByNamingThemInTheRequest() {
        UUID bobsId = userRepository.findByUsernameOrEmail("bob").orElseThrow().getPublicId();

        for (int i = 0; i < 3; i++) {
            submit(alice);
        }

        assertThat(alice.postJson(
                "/api/problems/" + problemId + "/submissions?userId=" + bobsId,
                new SubmissionRequest(Language.PYTHON, "print(1)")).getStatusCode())
                .as("a request parameter cannot change who you are")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(submit(bob).getStatusCode())
                .as("and bob's allowance was never touched")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Nothing in the request may set a limit, a capacity or a window. */
    @Test
    void ignoresClientSuppliedLimitParameters() {
        for (int i = 0; i < 3; i++) {
            submit(alice);
        }

        assertThat(alice.postJson(
                "/api/problems/" + problemId + "/submissions?limit=1000&capacity=1000&rateLimit=off",
                new SubmissionRequest(Language.PYTHON, "print(1)")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Different policies must not share a bucket, or one endpoint would starve another. */
    @Test
    void keepsOnePoliciesAllowanceAwayFromAnothers() {
        for (int i = 0; i < 4; i++) {
            submit(alice);
        }

        assertThat(alice.getJson("/api/problems?search=alpha").getStatusCode())
                .as("submitting does not consume the search allowance")
                .isEqualTo(HttpStatus.OK);
    }

    /** Ordinary browsing is deliberately not limited; only searching is. */
    @Test
    void leavesPlainCatalogueBrowsingAlone() {
        for (int i = 0; i < 10; i++) {
            assertThat(alice.getJson("/api/problems").getStatusCode())
                    .as("a plain listing is an indexed read and is not limited")
                    .isEqualTo(HttpStatus.OK);
        }

        for (int i = 0; i < 3; i++) {
            assertThat(alice.getJson("/api/problems?search=alpha").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(alice.getJson("/api/problems?search=alpha").getStatusCode())
                .as("but a search term is")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // =============================================================== response and state

    @Test
    void advertisesTheAllowanceAndTheWaitInHeaders() {
        ResponseEntity<Map<String, Object>> allowed = submit(alice);

        assertThat(allowed.getHeaders().getFirst(RateLimitHeaders.LIMIT)).isEqualTo("3");
        assertThat(allowed.getHeaders().getFirst(RateLimitHeaders.REMAINING)).isEqualTo("2");
        assertThat(allowed.getHeaders().getFirst("Retry-After"))
                .as("a successful response has nothing to retry")
                .isNull();

        submit(alice);
        submit(alice);
        ResponseEntity<Map<String, Object>> refused = submit(alice);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(RateLimitHeaders.REMAINING)).isEqualTo("0");
        assertThat(Integer.parseInt(refused.getHeaders().getFirst("Retry-After")))
                .as("an exact wait, because a token bucket refills at a known rate")
                .isBetween(1, 60);
    }

    /** The error body is the project's standard envelope and leaks nothing. */
    @Test
    void tellsTheClientNothingAboutTheMechanism() {
        for (int i = 0; i < 3; i++) {
            submit(alice);
        }

        Map<String, Object> body = submit(alice).getBody();

        assertThat(body).containsEntry("status", 429).containsEntry("error", "RATE_LIMITED");
        assertThat(String.valueOf(body))
                .doesNotContainIgnoringCase("redis")
                .doesNotContainIgnoringCase("bucket")
                .doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("codearena:rl")
                .doesNotContainIgnoringCase("lua")
                .doesNotContainIgnoringCase("script");
    }

    /**
     * Every bucket must expire on its own. A key per identity with no TTL is a memory leak
     * an attacker gets to choose the size of.
     */
    @Test
    void expiresEveryBucketItCreates() {
        submit(alice);
        attemptLogin("alice", "wrong");
        register("someone-new");

        Set<String> keys = redis.keys(RateLimitService.KEY_PREFIX + "*");
        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(key ->
                assertThat(redis.getExpire(key))
                        .as("%s must expire", key)
                        .isGreaterThan(0));
    }

    /**
     * Key cardinality must be bounded by real identities, not by attacker imagination. A
     * thousand invented usernames must not become a thousand differently-shaped keys, and
     * must not put any of those usernames in the keyspace.
     */
    @Test
    void boundsTheKeysAnAttackerCanCreate() {
        clearBuckets();

        for (int i = 0; i < 30; i++) {
            attemptLogin("invented-account-" + i + "-" + "x".repeat(200), "wrong");
        }

        Set<String> keys = redis.keys(RateLimitService.KEY_PREFIX + RateLimitPolicy.LOGIN_ACCOUNT.id() + "*");
        assertThat(keys).allSatisfy(key -> {
            assertThat(key)
                    .as("an attempted username must never be readable from the keyspace")
                    .doesNotContain("invented-account");
            assertThat(key.length())
                    .as("every key is the same bounded size, whatever was sent")
                    .isLessThan(80);
        });
    }

    /** Nothing about a rate-limit key may reveal a session, a token or a password. */
    @Test
    void putsNoCredentialIntoItsKeysOrValues() {
        attemptLogin("alice", "hunter2-the-actual-password");
        submit(alice);

        Set<String> keys = redis.keys(RateLimitService.KEY_PREFIX + "*");
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            assertThat(key).doesNotContain("hunter2");
            assertThat(String.valueOf(redis.opsForHash().entries(key)))
                    .doesNotContain("hunter2")
                    .doesNotContain(PASSWORD);
        }
        assertThat(String.valueOf(keys)).doesNotContain(alice.cookieHeader());
    }

    // =============================================================== helpers

    private void clearBuckets() {
        Set<String> keys = redis.keys(RateLimitService.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        Set<String> markers = redis.keys(RateLimitViolationAuditor.MARKER_PREFIX + "*");
        if (markers != null && !markers.isEmpty()) {
            redis.delete(markers);
        }
    }

    private ResponseEntity<Map<String, Object>> attemptLogin(String identifier, String password) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        return client.postJson("/api/auth/login", new LoginRequest(identifier, password));
    }

    private ResponseEntity<Map<String, Object>> register(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        return client.postJson("/api/auth/register",
                new RegistrationRequest(username, username + "@example.com", PASSWORD));
    }

    private ResponseEntity<Map<String, Object>> submit(BrowserClient client) {
        return client.postJson("/api/problems/" + problemId + "/submissions",
                new SubmissionRequest(Language.PYTHON, "print(1)"));
    }

    private ResponseEntity<Map<String, Object>> submitToContest(BrowserClient client, UUID contestId) {
        return client.postJson(
                "/api/contests/" + contestId + "/problems/" + problemId + "/submissions",
                new SubmissionRequest(Language.PYTHON, "print(1)"));
    }

    private long queueDepth() {
        Long depth = redis.opsForList().size(SubmissionQueue.PENDING);
        return depth == null ? 0 : depth;
    }

    private long submissionCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM submissions", Long.class);
        return count == null ? 0 : count;
    }

    private boolean userIsStillEnabled(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT enabled FROM users WHERE username = ?", Boolean.class, username));
    }

    /**
     * A published contest running now, with the given contestant registered.
     *
     * <p>The schedule is moved back with SQL afterwards, mirroring what the passage of an
     * hour would do: a contest cannot be published after it has started, nor registered for
     * once it is running.
     */
    private UUID liveContestWith(BrowserClient contestant) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Rate limited contest");
        body.put("slug", "rate-limited");
        body.put("description", "A contest.");
        body.put("startAt", hoursFromNow(1).toString());
        body.put("endAt", hoursFromNow(4).toString());

        UUID contestId = UUID.fromString(
                admin.postJson("/api/admin/contests", body).getBody().get("id").toString());
        admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", problemId.toString(), "points", 100));
        admin.postJson("/api/admin/contests/" + contestId + "/publish", null);
        contestant.postJson("/api/contests/" + contestId + "/register", null);

        jdbc.update("UPDATE contests SET start_at = ?, end_at = ? WHERE public_id = ?",
                java.sql.Timestamp.from(hoursFromNow(-1)),
                java.sql.Timestamp.from(hoursFromNow(2)), contestId);
        return contestId;
    }

    private static Instant hoursFromNow(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(
                User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private UUID createPublishedProblem() {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.findByUsernameOrEmail("boss").orElseThrow();
            Problem problem = Problem.createDraft("alpha", "Alpha", Difficulty.EASY, owner);
            problem.updateContent("Alpha", "Add two numbers.", "Two integers.", "Their sum.",
                    "1 <= a, b <= 100", null, Difficulty.EASY, 2000, 256,
                    Set.of(ProblemTag.ARRAY), owner);
            problem.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", null)));
            problem.replaceTestCases(List.of(new Problem.TestCaseContent("1 2", "3", true, 1)));
            problem.publish(owner);
            return problemRepository.saveAndFlush(problem).getPublicId();
        });
    }

    private BrowserClient signIn(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");
        assertThat(client.postJson("/api/auth/login", new LoginRequest(username, PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return client;
    }
}
