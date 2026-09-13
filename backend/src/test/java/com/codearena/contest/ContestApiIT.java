package com.codearena.contest;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemTag;
import com.codearena.queue.SubmissionQueue;
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
 * Contests over real HTTP against real PostgreSQL.
 *
 * <h2>How time is controlled</h2>
 * The production clock is used throughout — no clock is stubbed — and contests are placed
 * relative to {@code now} instead. To reach a state the rules forbid creating directly (an
 * ended contest cannot be published, and a live one cannot be rescheduled), the schedule is
 * moved with a direct UPDATE, which is exactly what the passage of time would have done.
 * That keeps every assertion running through the real derivation rather than a test double.
 *
 * <p>Exact boundary instants — at {@code startAt}, at {@code endAt}, a millisecond either
 * side — are covered by {@link ContestStatusTest}, where they can be asserted as equalities
 * rather than raced against a real clock.
 *
 * <h2>No worker runs here</h2>
 * Verdicts are written with SQL, the way the worker would. These tests are about scoring,
 * eligibility and disclosure; that real programs really run in real sandboxes is the
 * executor module's job, and the full loop is covered end to end against the live stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProblemRepository problemRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private BrowserClient admin;
    private BrowserClient alice;
    private BrowserClient bob;
    private UUID alphaProblem;
    private UUID betaProblem;
    private UUID draftProblem;

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

        alphaProblem = createProblem("Alpha", "alpha", true);
        betaProblem = createProblem("Beta", "beta", true);
        draftProblem = createProblem("Gamma Draft", "gamma-draft", false);
    }

    // =============================================================== creation

    @Test
    void createsAContestAsADraft() {
        ResponseEntity<Map<String, Object>> response = admin.postJson("/api/admin/contests", contestBody(
                "spring-2026", hoursFromNow(1), hoursFromNow(4)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "DRAFT");
    }

    @Test
    void refusesContestCreationToANormalUser() {
        assertThat(alice.postJson("/api/admin/contests",
                contestBody("sneaky", hoursFromNow(1), hoursFromNow(4))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refusesContestCreationToAnAnonymousCaller() {
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.getJson("/api/system/info");   // prime the CSRF cookie as a browser would

        assertThat(anonymous.postJson("/api/admin/contests",
                contestBody("anon", hoursFromNow(1), hoursFromNow(4))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refusesADuplicateSlug() {
        admin.postJson("/api/admin/contests", contestBody("twice", hoursFromNow(1), hoursFromNow(4)));

        assertThat(admin.postJson("/api/admin/contests",
                contestBody("twice", hoursFromNow(2), hoursFromNow(5))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusesAScheduleThatEndsBeforeItStarts() {
        assertThat(admin.postJson("/api/admin/contests",
                contestBody("backwards", hoursFromNow(4), hoursFromNow(1))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =============================================================== visibility

    /** A draft is indistinguishable from a contest that does not exist. */
    @Test
    void hidesDraftContestsFromNormalUsers() {
        UUID contestId = createDraft("hidden", hoursFromNow(1), hoursFromNow(4));

        ResponseEntity<Map<String, Object>> list = alice.getJson("/api/contests");
        assertThat(itemSlugs(list)).doesNotContain("hidden");

        assertThat(alice.getJson("/api/contests/" + contestId).getStatusCode())
                .as("a draft answers 404, not 403: 403 would confirm it exists")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void showsDraftsToAdministrators() {
        createDraft("admin-visible", hoursFromNow(1), hoursFromNow(4));

        assertThat(itemSlugs(admin.getJson("/api/admin/contests"))).contains("admin-visible");
    }

    @Test
    void showsAPublishedContestToUsersWithItsDerivedStatus() {
        UUID contestId = createPublished("visible", hoursFromNow(1), hoursFromNow(4));

        Map<String, Object> detail = alice.getJson("/api/contests/" + contestId).getBody();
        assertThat(detail).containsEntry("status", "UPCOMING");
        assertThat(detail).containsEntry("registered", false);
        assertThat(detail).containsKey("serverTime");
    }

    /**
     * Publishing announces that a contest exists, not what is in it.
     *
     * <p>Releasing the problem set during UPCOMING would let registered users read every
     * statement in advance and start solving before the clock did.
     */
    @Test
    void withholdsTheProblemSetUntilTheContestStarts() {
        UUID upcoming = createPublished("upcoming", hoursFromNow(1), hoursFromNow(4));
        register(alice, upcoming);

        Map<String, Object> before = alice.getJson("/api/contests/" + upcoming).getBody();
        assertThat((List<?>) before.get("problems")).isEmpty();

        UUID live = liveContest("live-now", alice);
        Map<String, Object> during = alice.getJson("/api/contests/" + live).getBody();
        assertThat((List<?>) during.get("problems")).isNotEmpty();
    }

    // =============================================================== problems

    @Test
    void refusesToAddAProblemThatIsNotPublished() {
        UUID contestId = createDraft("draftprob", hoursFromNow(1), hoursFromNow(4));

        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", draftProblem.toString(), "points", 100)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesTheSameProblemTwice() {
        UUID contestId = createDraft("dupe", hoursFromNow(1), hoursFromNow(4));
        addProblem(contestId, alphaProblem, 100);

        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", alphaProblem.toString(), "points", 100)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void labelsProblemsInOrder() {
        UUID contestId = createDraft("labels", hoursFromNow(1), hoursFromNow(4));
        addProblem(contestId, alphaProblem, 100);
        addProblem(contestId, betaProblem, 200);

        List<Map<String, Object>> problems = problemsOf(admin, contestId);
        assertThat(problems).extracting(entry -> entry.get("label")).containsExactly("A", "B");
    }

    @Test
    void reordersProblemsAndRelabelsThem() {
        UUID contestId = createDraft("reorder", hoursFromNow(1), hoursFromNow(4));
        addProblem(contestId, alphaProblem, 100);
        addProblem(contestId, betaProblem, 200);

        admin.putJson("/api/admin/contests/" + contestId + "/problems/" + betaProblem,
                Map.of("displayOrder", 0));

        List<Map<String, Object>> problems = problemsOf(admin, contestId);
        assertThat(problems).extracting(entry -> entry.get("problemSlug")).containsExactly("beta", "alpha");
        assertThat(problems).extracting(entry -> entry.get("label")).containsExactly("A", "B");
    }

    /**
     * The freeze. Changing points mid-contest silently rewrites the standings of everyone who
     * has already solved the problem.
     */
    @Test
    void refusesToChangeProblemsOnceTheContestIsLive() {
        UUID contestId = liveContest("frozen");

        assertThat(admin.putJson("/api/admin/contests/" + contestId + "/problems/" + alphaProblem,
                Map.of("points", 999)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", betaProblem.toString(), "points", 100)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesToRescheduleALiveContest() {
        UUID contestId = liveContest("no-reschedule");

        assertThat(admin.putJson("/api/admin/contests/" + contestId,
                contestBody("no-reschedule", hoursFromNow(-1), hoursFromNow(9))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =============================================================== registration

    @Test
    void registersAUserForAnUpcomingContest() {
        UUID contestId = createPublished("register-me", hoursFromNow(1), hoursFromNow(4));

        ResponseEntity<Map<String, Object>> response = alice.postJson(
                "/api/contests/" + contestId + "/register", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("alreadyRegistered", false);
        assertThat(alice.getJson("/api/contests/" + contestId).getBody())
                .containsEntry("registered", true);
    }

    /** A double-click must not be an error the user cannot act on. */
    @Test
    void treatsASecondRegistrationAsSuccess() {
        UUID contestId = createPublished("twice-register", hoursFromNow(1), hoursFromNow(4));
        register(alice, contestId);

        ResponseEntity<Map<String, Object>> second = alice.postJson(
                "/api/contests/" + contestId + "/register", null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).containsEntry("alreadyRegistered", true);
        assertThat(participantCount(contestId)).isEqualTo(1);
    }

    @Test
    void closesRegistrationWhenTheContestStarts() {
        UUID contestId = liveContest("too-late");

        assertThat(alice.postJson("/api/contests/" + contestId + "/register", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusesRegistrationToAnAnonymousCaller() {
        UUID contestId = createPublished("anon-register", hoursFromNow(1), hoursFromNow(4));
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.getJson("/api/system/info");

        assertThat(anonymous.postJson("/api/contests/" + contestId + "/register", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The unique constraint, not the pre-check, is what prevents a double registration: two
     * concurrent requests can both pass a check and only one can win an index.
     */
    @Test
    void createsExactlyOneRegistrationUnderConcurrentRequests() throws Exception {
        UUID contestId = createPublished("stampede", hoursFromNow(1), hoursFromNow(4));

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    BrowserClient client = signIn("alice");
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        if (client.postJson("/api/contests/" + contestId + "/register", null)
                                .getStatusCode().is2xxSuccessful()) {
                            accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(20, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(participantCount(contestId))
                .as("however many requests raced, there is exactly one registration")
                .isEqualTo(1);
        assertThat(accepted.get()).isGreaterThan(0);
    }

    // =============================================================== submissions

    @Test
    void acceptsASubmissionFromARegisteredUserDuringALiveContest() {
        UUID contestId = liveContest("submitting", alice);

        ResponseEntity<Map<String, Object>> response = submitToContest(alice, contestId, alphaProblem);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "QUEUED");

        UUID submissionId = UUID.fromString(response.getBody().get("submissionId").toString());
        assertThat(contestIdOf(submissionId))
                .as("the submission is bound to the contest server-side")
                .isNotNull();
    }

    @Test
    void refusesASubmissionFromSomebodyWhoNeverRegistered() {
        UUID contestId = liveContest("unregistered");

        assertThat(submitToContest(bob, contestId, alphaProblem).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Knowing a problem's id is not authorisation to submit it to a contest.
     *
     * <p>The association is read from the database rather than inferred from two ids
     * appearing together in a URL.
     */
    @Test
    void refusesASubmissionToAProblemThatIsNotInTheContest() {
        UUID contestId = liveContest("wrong-problem", alice);   // contains alpha only

        assertThat(submitToContest(alice, contestId, betaProblem).getStatusCode())
                .as("beta is a real, published problem -- just not in this contest")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesASubmissionBeforeTheContestStarts() {
        // createPublished already adds alpha; publishing requires at least one problem.
        UUID contestId = createPublished("not-yet", hoursFromNow(1), hoursFromNow(4));
        register(alice, contestId);

        assertThat(submitToContest(alice, contestId, alphaProblem).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * The deadline is enforced by the server, whatever the browser believes.
     *
     * <p>A client whose countdown is still running — because its clock drifted, because the
     * tab was asleep, or because somebody set it back — is refused all the same.
     */
    @Test
    void refusesASubmissionAfterTheContestEnds() {
        UUID contestId = liveContest("finished", alice);
        moveSchedule(contestId, hoursFromNow(-4), hoursFromNow(-1));

        ResponseEntity<Map<String, Object>> response = submitToContest(alice, contestId, alphaProblem);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message").toString()).contains("ended");
    }

    @Test
    void refusesASubmissionToACancelledContest() {
        UUID contestId = liveContest("called-off", alice);
        admin.postJson("/api/admin/contests/" + contestId + "/cancel", null);

        assertThat(submitToContest(alice, contestId, alphaProblem).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusesAContestSubmissionFromAnAnonymousCaller() {
        UUID contestId = liveContest("anon-submit");
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.getJson("/api/system/info");

        assertThat(anonymous.postJson(
                "/api/contests/" + contestId + "/problems/" + alphaProblem + "/submissions",
                new SubmissionRequest(Language.PYTHON, "print(1)")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Practice and contest submissions are the same rows, told apart by one nullable column. */
    @Test
    void keepsPracticeSubmissionsOutOfTheContest() {
        UUID contestId = liveContest("separation", alice);

        submitToContest(alice, contestId, alphaProblem);
        alice.postJson("/api/problems/" + alphaProblem + "/submissions",
                new SubmissionRequest(Language.PYTHON, "print(1)"));

        Integer practice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE contest_id IS NULL", Integer.class);
        Integer contest = jdbc.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE contest_id IS NOT NULL", Integer.class);

        assertThat(practice).isEqualTo(1);
        assertThat(contest).isEqualTo(1);
    }

    /**
     * A client cannot promote its own practice submission into a contest, or dictate a
     * verdict, by adding fields to the request body.
     */
    @Test
    void ignoresContestAndVerdictFieldsSentByTheClient() {
        UUID contestId = liveContest("overpost", alice);

        Map<String, Object> overposted = new HashMap<>();
        overposted.put("language", "PYTHON");
        overposted.put("sourceCode", "print(1)");
        overposted.put("contestId", contestId.toString());
        overposted.put("status", "ACCEPTED");
        overposted.put("score", 9999);

        ResponseEntity<Map<String, Object>> response =
                alice.postJson("/api/problems/" + alphaProblem + "/submissions", overposted);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "QUEUED");

        UUID submissionId = UUID.fromString(response.getBody().get("submissionId").toString());
        assertThat(contestIdOf(submissionId))
                .as("a practice submission stays practice however the body is decorated")
                .isNull();
    }

    // =============================================================== standings

    @Test
    void scoresASolveAndChargesTimeAndRejections() {
        UUID contestId = liveContest("scoring", alice);

        // Two rejections, then a solve thirty minutes in.
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.WRONG_ANSWER, 5);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.RUNTIME_ERROR, 10);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 30);

        Map<String, Object> row = firstRow(contestId);
        assertThat(row).containsEntry("score", 100);
        // 30 elapsed + 2 × 20
        assertThat(row).containsEntry("penalty", 70);
        assertThat(row).containsEntry("solved", 1);
    }

    /**
     * A judge outage is ours, not the contestant's.
     *
     * <p>If SYSTEM_ERROR were counted, a sandbox that failed to start would cost a
     * contestant twenty minutes for nothing.
     */
    @Test
    void neverChargesAContestantForASystemError() {
        UUID contestId = liveContest("infra", alice);

        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.SYSTEM_ERROR, 3);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.SYSTEM_ERROR, 6);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 20);

        Map<String, Object> row = firstRow(contestId);
        assertThat(row)
                .as("only the twenty elapsed minutes; the two judge failures cost nothing")
                .containsEntry("penalty", 20);
    }

    /** Submitting the same correct answer again cannot change a score or a penalty. */
    @Test
    void countsASolveOnceHoweverOftenItIsResubmitted() {
        UUID contestId = liveContest("resubmit", alice);

        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 10);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 40);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 50);

        Map<String, Object> row = firstRow(contestId);
        assertThat(row).containsEntry("score", 100);
        assertThat(row).containsEntry("solved", 1);
        assertThat(row).as("the first solve is the one that counts").containsEntry("penalty", 10);
    }

    @Test
    void ranksByScoreThenPenalty() {
        UUID contestId = liveContest("ranking", alice, bob);

        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 50);
        recordSubmission(contestId, "bob", alphaProblem, SubmissionStatus.ACCEPTED, 10);

        List<Map<String, Object>> rows = rows(contestId);
        assertThat(rows.get(0)).containsEntry("username", "bob").containsEntry("rank", 1);
        assertThat(rows.get(1)).containsEntry("username", "alice").containsEntry("rank", 2);
    }

    @Test
    void listsContestantsWhoHaveSolvedNothing() {
        UUID contestId = liveContest("nobody-solved", alice, bob);

        assertThat(rows(contestId)).hasSize(2);
        assertThat(rows(contestId)).allSatisfy(row -> assertThat(row).containsEntry("score", 0));
    }

    /** A scoreboard is the most widely read page a contest has. */
    @Test
    void keepsPrivateDataOutOfTheStandings() {
        UUID contestId = liveContest("privacy", alice);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 5);

        String body = alice.get("/api/contests/" + contestId + "/standings", String.class).getBody();

        assertThat(body).contains("alice");
        assertThat(body).doesNotContain("alice@example.com");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("sourceCode");
    }

    @Test
    void refusesStandingsForADraftContest() {
        UUID contestId = createDraft("no-standings", hoursFromNow(1), hoursFromNow(4));

        assertThat(alice.getJson("/api/contests/" + contestId + "/standings").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void servesEmptyStandingsForAContestThatHasNotStarted() {
        UUID contestId = createPublished("not-started", hoursFromNow(1), hoursFromNow(4));

        Map<String, Object> standings = alice.getJson("/api/contests/" + contestId + "/standings").getBody();
        assertThat((List<?>) standings.get("rows")).isEmpty();
        assertThat(standings).containsEntry("status", "UPCOMING");
    }

    /**
     * The standings stay readable after a contest is cancelled.
     *
     * <p>People competed; what they did is a record, and hiding it would be a second penalty
     * for somebody else's decision to call the contest off.
     */
    @Test
    void keepsStandingsReadableAfterCancellation() {
        UUID contestId = liveContest("cancelled-standings", alice);
        recordSubmission(contestId, "alice", alphaProblem, SubmissionStatus.ACCEPTED, 5);
        admin.postJson("/api/admin/contests/" + contestId + "/cancel", null);

        Map<String, Object> standings = alice.getJson("/api/contests/" + contestId + "/standings").getBody();
        assertThat(standings).containsEntry("status", "CANCELLED");
    }

    // =============================================================== admin views

    @Test
    void listsParticipantsForAdministratorsWithoutExposingEmail() {
        UUID contestId = createPublished("participants", hoursFromNow(1), hoursFromNow(4));
        register(alice, contestId);

        String body = admin.get("/api/admin/contests/" + contestId + "/participants", String.class).getBody();

        assertThat(body).contains("alice");
        assertThat(body).doesNotContain("alice@example.com");
    }

    @Test
    void refusesTheParticipantListToNormalUsers() {
        UUID contestId = createPublished("participants-private", hoursFromNow(1), hoursFromNow(4));

        assertThat(alice.getJson("/api/admin/contests/" + contestId + "/participants").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =============================================================== deletion

    @Test
    void deletesAnUntouchedDraft() {
        UUID contestId = createDraft("throwaway", hoursFromNow(1), hoursFromNow(4));

        assertThat(admin.delete("/api/admin/contests/" + contestId, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesToDeleteAContestWithParticipants() {
        UUID contestId = createPublished("has-people", hoursFromNow(1), hoursFromNow(4));
        register(alice, contestId);

        assertThat(admin.delete("/api/admin/contests/" + contestId, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // =============================================================== helpers

    private Map<String, Object> contestBody(String slug, Instant startAt, Instant endAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Contest " + slug);
        body.put("slug", slug);
        body.put("description", "A contest.");
        body.put("startAt", startAt.toString());
        body.put("endAt", endAt.toString());
        return body;
    }

    private static Instant hoursFromNow(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    }

    private UUID createDraft(String slug, Instant startAt, Instant endAt) {
        ResponseEntity<Map<String, Object>> response =
                admin.postJson("/api/admin/contests", contestBody(slug, startAt, endAt));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private UUID createPublished(String slug, Instant startAt, Instant endAt) {
        UUID contestId = createDraft(slug, startAt, endAt);
        addProblem(contestId, alphaProblem, 100);
        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/publish", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return contestId;
    }

    /**
     * A published contest that is running now, with the given contestants registered.
     *
     * <p>The order matters and mirrors reality: a contest is created in the future,
     * published, registered for, and only then starts. Publishing a contest that has
     * already begun is not something an administrator can do, and registering for one that
     * has is not something a contestant can do — so the schedule is moved back with SQL at
     * the end, which is exactly what the passage of an hour would have done.
     */
    private UUID liveContest(String slug, BrowserClient... contestants) {
        UUID contestId = createPublished(slug, hoursFromNow(1), hoursFromNow(4));
        for (BrowserClient contestant : contestants) {
            register(contestant, contestId);
        }
        moveSchedule(contestId, hoursFromNow(-1), hoursFromNow(2));
        return contestId;
    }

    private void moveSchedule(UUID contestId, Instant startAt, Instant endAt) {
        jdbc.update("UPDATE contests SET start_at = ?, end_at = ? WHERE public_id = ?",
                java.sql.Timestamp.from(startAt), java.sql.Timestamp.from(endAt), contestId);
    }

    private void addProblem(UUID contestId, UUID problemId, int points) {
        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", problemId.toString(), "points", points))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void register(BrowserClient client, UUID contestId) {
        assertThat(client.postJson("/api/contests/" + contestId + "/register", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> submitToContest(BrowserClient client,
                                                                UUID contestId, UUID problemId) {
        return client.postJson(
                "/api/contests/" + contestId + "/problems/" + problemId + "/submissions",
                new SubmissionRequest(Language.PYTHON, "print(1)"));
    }

    /**
     * Writes a judged submission directly, as the worker would.
     *
     * <p>{@code createdAt} is set explicitly so a solve can be placed a known number of
     * minutes into the contest — the penalty depends on it, and waiting thirty real minutes
     * to test a thirty-minute penalty is not a test.
     */
    private void recordSubmission(UUID contestId, String username, UUID problemId,
                                  SubmissionStatus status, int minutesIntoContest) {
        Instant startAt = jdbc.queryForObject(
                "SELECT start_at FROM contests WHERE public_id = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), contestId);
        Instant createdAt = startAt.plus(minutesIntoContest, ChronoUnit.MINUTES);

        jdbc.update("""
                INSERT INTO submissions
                    (public_id, problem_id, user_id, contest_id, language, source_code,
                     status, created_at, updated_at, finished_at, attempts)
                VALUES (gen_random_uuid(),
                        (SELECT id FROM problems WHERE public_id = ?),
                        (SELECT id FROM users WHERE username = ?),
                        (SELECT id FROM contests WHERE public_id = ?),
                        'PYTHON', 'print(1)', ?, ?, ?, ?, 1)
                """,
                problemId, username, contestId, status.name(),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));
    }

    private List<Map<String, Object>> rows(UUID contestId) {
        Map<String, Object> standings =
                alice.getJson("/api/contests/" + contestId + "/standings").getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) standings.get("rows");
        return rows;
    }

    private Map<String, Object> firstRow(UUID contestId) {
        List<Map<String, Object>> rows = rows(contestId);
        assertThat(rows).isNotEmpty();
        return rows.getFirst();
    }

    private List<Map<String, Object>> problemsOf(BrowserClient client, UUID contestId) {
        Map<String, Object> detail = client.getJson("/api/admin/contests/" + contestId).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) detail.get("problems");
        return problems;
    }

    private List<String> itemSlugs(ResponseEntity<Map<String, Object>> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        return items.stream().map(item -> String.valueOf(item.get("slug"))).toList();
    }

    private Long contestIdOf(UUID submissionId) {
        return jdbc.queryForObject("SELECT contest_id FROM submissions WHERE public_id = ?",
                Long.class, submissionId);
    }

    private int participantCount(UUID contestId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contest_participants p
                JOIN contests c ON c.id = p.contest_id
                WHERE c.public_id = ?
                """, Integer.class, contestId);
        return count == null ? 0 : count;
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(
                User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private UUID createProblem(String title, String slug, boolean published) {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.findByUsernameOrEmail("boss").orElseThrow();
            Problem problem = Problem.createDraft(slug, title, Difficulty.EASY, owner);
            problem.updateContent(title, "Add two numbers.", "Two integers.", "Their sum.",
                    "1 <= a, b <= 100", null, Difficulty.EASY, 2000, 256,
                    Set.of(ProblemTag.ARRAY), owner);
            problem.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", null)));
            problem.replaceTestCases(List.of(
                    new Problem.TestCaseContent("1 2", "3", true, 1)));
            if (published) {
                problem.publish(owner);
            }
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
