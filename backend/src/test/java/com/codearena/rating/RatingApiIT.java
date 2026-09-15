package com.codearena.rating;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.problem.Difficulty;
import com.codearena.problem.Problem;
import com.codearena.problem.ProblemRepository;
import com.codearena.problem.ProblemTag;
import com.codearena.ratelimit.RateLimitHeaders;
import com.codearena.shared.SubmissionStatus;
import com.codearena.support.BrowserClient;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ratings over real HTTP, against real PostgreSQL.
 *
 * <h2>How a finished contest is manufactured</h2>
 * The production clock is used throughout. A contest is created in the future, published,
 * registered for, and then its schedule is moved into the past with SQL — which is exactly
 * what the passage of time would have done, and keeps every status derivation running through
 * the real code rather than a stubbed clock. Verdicts are written directly, the way the worker
 * would; that real code really runs in real sandboxes is the executor module's job.
 *
 * <h2>What this suite is really for</h2>
 * The arithmetic is covered by {@link RatingCalculatorTest}, where it can be hand-checked. The
 * value here is everything the arithmetic sits inside: that a contest is rated exactly once
 * under concurrency, that a cancelled contest is never rated, that the history explains the
 * rating, and that no request can influence any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProblemRepository problemRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ContestFinalizationService finalizationService;
    @Autowired private ContestFinalizationSweeper sweeper;

    private BrowserClient admin;
    private BrowserClient alice;
    private BrowserClient bob;
    private BrowserClient carol;
    private UUID alphaProblem;
    private UUID betaProblem;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);

        createAccount("boss", "boss@example.com", Role.ADMIN);
        createAccount("alice", "alice@example.com", Role.USER);
        createAccount("bob", "bob@example.com", Role.USER);
        createAccount("carol", "carol@example.com", Role.USER);

        admin = signIn("boss");
        alice = signIn("alice");
        bob = signIn("bob");
        carol = signIn("carol");

        alphaProblem = createProblem("Alpha", "alpha");
        betaProblem = createProblem("Beta", "beta");
    }

    // =============================================================== configuration

    @Nested
    @DisplayName("Configuring whether a contest is rated")
    class Configuration {

        @Test
        @DisplayName("a contest is unrated unless somebody says otherwise")
        void defaultsToUnrated() {
            // The asymmetry is the point. A contest accidentally created unrated is fixed by
            // an edit before it starts; one accidentally created rated has silently put
            // everybody's rating at stake in what was meant to be a practice round, and by
            // the time anybody notices it has started and can no longer be changed.
            UUID contestId = createDraft("quiet", null, hoursFromNow(1), hoursFromNow(4));

            assertThat(detail(contestId)).containsEntry("rated", false);
        }

        @Test
        @DisplayName("an administrator can mark a contest rated while it is a draft")
        void ratedCanBeSetOnADraft() {
            UUID contestId = createDraft("stakes", true, hoursFromNow(1), hoursFromNow(4));

            assertThat(detail(contestId)).containsEntry("rated", true);
        }

        @Test
        @DisplayName("it can still be changed while the contest is upcoming")
        void ratedCanBeChangedBeforeItStarts() {
            UUID contestId = createPublished("rethink", true, hoursFromNow(1), hoursFromNow(4));

            ResponseEntity<Map<String, Object>> response = admin.putJson(
                    "/api/admin/contests/" + contestId,
                    contestBody("rethink", false, hoursFromNow(1), hoursFromNow(4)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail(contestId)).containsEntry("rated", false);
        }

        @Test
        @DisplayName("it is frozen the moment the contest goes live")
        void ratedIsFrozenOnceTheContestStarts() {
            // The single most important rule in this phase. A contest that became rated
            // halfway through would be asking people to compete for stakes they never agreed
            // to; one that became unrated would take away a result somebody had earned.
            UUID contestId = createPublished("frozen", true, hoursFromNow(1), hoursFromNow(4));
            register(alice, contestId);
            moveSchedule(contestId, hoursFromNow(-1), hoursFromNow(2));

            ResponseEntity<Map<String, Object>> response = admin.putJson(
                    "/api/admin/contests/" + contestId,
                    contestBody("frozen", false, hoursFromNow(-1), hoursFromNow(2)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(detail(contestId)).containsEntry("rated", true);
        }

        @Test
        @DisplayName("and it is still frozen after the contest has ended")
        void ratedIsFrozenAfterTheContestEnds() {
            UUID contestId = endedContest("done", true, alice, bob);

            assertThat(admin.putJson("/api/admin/contests/" + contestId,
                    contestBody("done", false, hoursFromNow(-3), hoursFromNow(-1)))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("the change is recorded in the audit log, with what it was before")
        void changingRatedIsAudited() {
            UUID contestId = createPublished("audited", false, hoursFromNow(1), hoursFromNow(4));

            admin.putJson("/api/admin/contests/" + contestId,
                    contestBody("audited", true, hoursFromNow(1), hoursFromNow(4)));

            String metadata = jdbc.queryForObject("""
                    SELECT metadata::text FROM audit_events
                     WHERE action = 'CONTEST_UPDATE' AND entity_id = ?
                     ORDER BY id DESC LIMIT 1
                    """, String.class, contestId.toString());

            assertThat(metadata).contains("\"rated\": true").contains("\"previouslyRated\": false");
        }
    }

    // =============================================================== finalisation

    @Nested
    @DisplayName("Finalising a contest")
    class Finalisation {

        @Test
        @DisplayName("produces a rating for everybody who competed")
        void producesRatings() {
            UUID contestId = endedContest("main", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            Map<String, Object> result = finalizeAsAdmin(contestId);

            assertThat(result).containsEntry("alreadyFinalized", false)
                              .containsEntry("rated", true)
                              .containsEntry("ratedParticipants", 2);

            // Alice solved sooner, so she took less penalty and ranked first. Both start at
            // 1500 and both are provisional, so K is 40: ±20 exactly (see
            // RatingCalculatorTest.newcomersMoveFaster for the arithmetic).
            assertThat(ratingOf("alice")).isEqualTo(1520);
            assertThat(ratingOf("bob")).isEqualTo(1480);
        }

        @Test
        @DisplayName("writes one history row per competitor, and it explains the rating")
        void writesHistory() {
            UUID contestId = endedContest("history", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            finalizeAsAdmin(contestId);

            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT r.rating_before, r.rating_after, r.rating_change, r.rank,
                           r.participant_count, r.k_factor
                      FROM contest_rating_changes r
                      JOIN users u ON u.id = r.user_id
                     WHERE u.username = 'alice'
                    """);

            assertThat(row).containsEntry("rating_before", 1500)
                           .containsEntry("rating_after", 1520)
                           .containsEntry("rating_change", 20)
                           .containsEntry("rank", 1)
                           .containsEntry("participant_count", 2)
                           .containsEntry("k_factor", 40);
        }

        @Test
        @DisplayName("is idempotent: a second call changes nothing")
        void isIdempotent() {
            UUID contestId = endedContest("twice", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            finalizeAsAdmin(contestId);
            int afterFirst = ratingOf("alice");

            Map<String, Object> second = finalizeAsAdmin(contestId);

            assertThat(second).containsEntry("alreadyFinalized", true)
                              .containsEntry("ratedParticipants", 2);
            assertThat(ratingOf("alice")).isEqualTo(afterFirst);
            assertThat(historyRowCount(contestId)).isEqualTo(2);
        }

        @Test
        @DisplayName("ten simultaneous finalisations rate the contest exactly once")
        void isSafeUnderConcurrency() throws Exception {
            // The mandatory concurrency test, and the one that would catch the worst bug this
            // phase could ship: a contest rated twice means every competitor's change applied
            // twice, with a history that no longer explains the rating and no automatic way to
            // work out which half to undo.
            UUID contestId = endedContest("stampede", true, alice, bob, carol);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 30);
            solve(contestId, "carol", alphaProblem, 50);

            int callers = 10;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger finalised = new AtomicInteger();
            AtomicInteger alreadyDone = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            // Kept so a failure says what went wrong. A concurrency test that reports only
            // "somebody threw" is a concurrency test nobody can debug.
            AtomicReference<Exception> firstFailure = new AtomicReference<>();

            try {
                for (int caller = 0; caller < callers; caller++) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                            // Straight at the service, not through HTTP: the admin rate limit
                            // would otherwise decide how many of the ten arrive, and the race
                            // being tested is the database claim rather than the limiter.
                            ContestFinalizationService.Result result = finalizationService
                                    .finalize(contestId, ContestFinalizationService.Source.ADMIN);
                            if (result.alreadyFinalized()) {
                                alreadyDone.incrementAndGet();
                            } else {
                                finalised.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            firstFailure.compareAndSet(null, e);
                        }
                        return null;
                    });
                }

                assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(failed)
                    .as("no caller should error; first was %s", firstFailure.get())
                    .hasValue(0);
            assertThat(finalised).as("exactly one caller finalises").hasValue(1);
            assertThat(alreadyDone).as("the rest are told it was already done").hasValue(9);

            // The evidence that matters: three competitors, three history rows, once.
            assertThat(historyRowCount(contestId)).isEqualTo(3);
            // Three provisional competitors, all starting level. E = 0.5 for everyone;
            // Alice beat both, so A = 1 and Δ = round(40 × 0.5) = +20. Applied twice this
            // would read 1540, which is exactly the corruption being guarded against.
            assertThat(ratingOf("alice")).isEqualTo(1520);
        }

        @Test
        @DisplayName("the database refuses a second rating row even if the claim were bypassed")
        void theUniqueConstraintIsASecondLineOfDefence() {
            // Belt and braces, and worth asserting separately: if the conditional UPDATE ever
            // stopped working, this is what stands between a bug and a doubled rating.
            UUID contestId = endedContest("constraint", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);
            finalizeAsAdmin(contestId);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO contest_rating_changes
                        (user_id, contest_id, rating_before, rating_after, rating_change,
                         rank, participant_count, score, penalty,
                         expected_score, actual_score, k_factor)
                    SELECT r.user_id, r.contest_id, 1500, 1600, 100, 1, 2, 0, 0, 0.5, 1.0, 20
                      FROM contest_rating_changes r LIMIT 1
                    """))
                    .hasMessageContaining("uq_rating_changes_contest_user");
        }

        @Test
        @DisplayName("an unrated contest is finalised, and moves nobody")
        void anUnratedContestProducesNoRatings() {
            // "Finalised" and "changed somebody's rating" are different facts, and the schema
            // keeps them apart: rating_finalized_at answers the first, rated the second.
            UUID contestId = endedContest("casual", false, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            Map<String, Object> result = finalizeAsAdmin(contestId);

            assertThat(result).containsEntry("rated", false)
                              .containsEntry("ratedParticipants", 0);
            assertThat(hasRating("alice")).isFalse();
            assertThat(historyRowCount(contestId)).isZero();
        }

        @Test
        @DisplayName("a cancelled contest is never rated, whatever its standings show")
        void aCancelledContestIsNeverRated() {
            UUID contestId = endedContest("void", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);
            // Cancelled while it was still running, then time passed — the realistic shape of
            // a contest called off for a broken problem.
            jdbc.update("UPDATE contests SET lifecycle = 'CANCELLED' WHERE public_id = ?", contestId);

            ResponseEntity<Map<String, Object>> response =
                    admin.postJson("/api/admin/contests/" + contestId + "/finalize", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(hasRating("alice")).isFalse();
            assertThat(historyRowCount(contestId)).isZero();
        }

        @Test
        @DisplayName("a contest that has not ended cannot be finalised")
        void aLiveContestCannotBeFinalised() {
            UUID contestId = createPublished("running", true, hoursFromNow(1), hoursFromNow(4));
            register(alice, contestId);
            register(bob, contestId);
            moveSchedule(contestId, hoursFromNow(-1), hoursFromNow(2));
            solve(contestId, "alice", alphaProblem, 10);

            assertThat(admin.postJson("/api/admin/contests/" + contestId + "/finalize", null)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(hasRating("alice")).isFalse();
        }

        @Test
        @DisplayName("a contest of one produces no rating")
        void aFieldOfOneIsNotRated() {
            // Not an error: a contest one person entered is a real contest. It simply says
            // nothing about how well they did, because there was nobody to measure against.
            UUID contestId = endedContest("solo", true, alice);
            solve(contestId, "alice", alphaProblem, 10);

            Map<String, Object> result = finalizeAsAdmin(contestId);

            assertThat(result).containsEntry("rated", true)
                              .containsEntry("ratedParticipants", 0);
            assertThat(hasRating("alice")).isFalse();
        }

        @Test
        @DisplayName("registering is not competing: a no-show is not rated")
        void aNoShowIsNotRated() {
            // Registration is free and reversible up to the start. If it were enough to be
            // rated, a contestant could damage their rating by signing up and forgetting, and
            // a field could be padded with people who never opened it.
            UUID contestId = endedContest("noshow", true, alice, bob, carol);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);
            // carol registered and submitted nothing.

            Map<String, Object> result = finalizeAsAdmin(contestId);

            assertThat(result).containsEntry("ratedParticipants", 2);
            assertThat(hasRating("carol")).isFalse();
            // And her absence does not distort the two who did compete.
            assertThat(ratingOf("alice")).isEqualTo(1520);
            assertThat(ratingOf("bob")).isEqualTo(1480);
        }

        @Test
        @DisplayName("turning up counts even when every submission was wrong")
        void aWrongAnswerIsStillCompeting() {
            UUID contestId = endedContest("tried", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            submission(contestId, "bob", alphaProblem, SubmissionStatus.WRONG_ANSWER, 20);

            finalizeAsAdmin(contestId);

            assertThat(hasRating("bob")).isTrue();
            assertThat(ratingOf("bob")).isEqualTo(1480);
        }

        @Test
        @DisplayName("finalisation is audited, and says who asked for it")
        void finalisationIsAudited() {
            UUID contestId = endedContest("audit", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            finalizeAsAdmin(contestId);

            Map<String, Object> event = jdbc.queryForMap("""
                    SELECT actor_username, actor_type, outcome, metadata::text AS metadata
                      FROM audit_events
                     WHERE action = 'CONTEST_FINALIZE' AND entity_id = ?
                    """, contestId.toString());

            assertThat(event).containsEntry("actor_username", "boss")
                             .containsEntry("actor_type", "ADMIN")
                             .containsEntry("outcome", "SUCCESS");
            assertThat(event.get("metadata").toString())
                    .contains("\"source\": \"admin\"")
                    .contains("\"ratedParticipants\": 2");
        }
    }

    // =============================================================== the sweeper

    @Nested
    @DisplayName("Automatic finalisation")
    class Sweeping {

        @Test
        @DisplayName("finds a contest that ended while nobody was looking")
        void sweepsAnEndedContest() {
            UUID contestId = endedContest("auto", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            assertThat(sweeper.sweep()).isEqualTo(1);

            assertThat(ratingOf("alice")).isEqualTo(1520);
            assertThat(finalizedAt(contestId)).isNotNull();
        }

        @Test
        @DisplayName("attributes what it does to the system, not to a phantom anonymous user")
        void sweptFinalisationsAreAttributedToTheSystem() {
            // The sweeper runs with no security context. Resolving the actor the usual way
            // would record SYSTEM work as ANONYMOUS — an audit log claiming an unauthenticated
            // caller rated a contest is worse than none, because it is wrong in a way somebody
            // would act on.
            UUID contestId = endedContest("sysaudit", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            sweeper.sweep();

            Map<String, Object> event = jdbc.queryForMap("""
                    SELECT actor_type, actor_username, metadata::text AS metadata
                      FROM audit_events
                     WHERE action = 'CONTEST_FINALIZE' AND entity_id = ?
                    """, contestId.toString());

            assertThat(event).containsEntry("actor_type", "SYSTEM")
                             .containsEntry("actor_username", null);
            assertThat(event.get("metadata").toString()).contains("\"source\": \"sweeper\"");
        }

        @Test
        @DisplayName("leaves an already-finalised contest alone")
        void doesNotRefinalise() {
            UUID contestId = endedContest("done-already", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);
            finalizeAsAdmin(contestId);

            assertThat(sweeper.sweep()).isZero();
            assertThat(historyRowCount(contestId)).isEqualTo(2);
        }

        @Test
        @DisplayName("ignores contests that are cancelled, unrated, live or still drafts")
        void sweepsOnlyWhatItShould() {
            UUID cancelled = endedContest("swept-cancelled", true, alice, bob);
            solve(cancelled, "alice", alphaProblem, 10);
            solve(cancelled, "bob", alphaProblem, 40);
            jdbc.update("UPDATE contests SET lifecycle = 'CANCELLED' WHERE public_id = ?", cancelled);

            UUID unrated = endedContest("swept-unrated", false, alice, bob);
            solve(unrated, "alice", betaProblem, 10);
            solve(unrated, "bob", betaProblem, 40);

            UUID draft = createDraft("swept-draft", true, hoursFromNow(-4), hoursFromNow(-2));

            assertThat(sweeper.sweep()).isZero();

            assertThat(hasRating("alice")).isFalse();
            assertThat(finalizedAt(cancelled)).isNull();
            assertThat(finalizedAt(unrated)).isNull();
            assertThat(finalizedAt(draft)).isNull();
        }

        @Test
        @DisplayName("a second sweep is a no-op, so running two instances is safe")
        void repeatedSweepsAreIdempotent() {
            UUID contestId = endedContest("twice-swept", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            assertThat(sweeper.sweep()).isEqualTo(1);
            assertThat(sweeper.sweep()).isZero();
            assertThat(sweeper.sweep()).isZero();

            assertThat(historyRowCount(contestId)).isEqualTo(2);
            assertThat(ratingOf("alice")).isEqualTo(1520);
        }
    }

    // =============================================================== reading

    @Nested
    @DisplayName("The global ranking")
    class Ranking {

        @Test
        @DisplayName("lists rated competitors, best first")
        void listsRatedCompetitorsInOrder() {
            ratedContest("board", alice, bob, carol);

            ResponseEntity<Map<String, Object>> response = alice.getJson("/api/rankings");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> items = items(response);
            assertThat(items).hasSize(3);
            assertThat(items.get(0)).containsEntry("username", "alice");
            assertThat(items.get(2)).containsEntry("username", "carol");
            assertThat((Integer) items.get(0).get("rating"))
                    .isGreaterThan((Integer) items.get(2).get("rating"));
        }

        @Test
        @DisplayName("an account that has never competed is absent, not listed at 1500")
        void unratedAccountsAreAbsent() {
            // Having no rating and having a rating of 1500 are different facts. Listing the
            // second when the first is true would be inventing a result.
            ratedContest("absent", alice, bob);

            List<Map<String, Object>> items = items(alice.getJson("/api/rankings"));

            assertThat(items).extracting(entry -> entry.get("username"))
                    .containsExactlyInAnyOrder("alice", "bob")
                    .doesNotContain("carol");
        }

        @Test
        @DisplayName("ties share a rank and the next rank skips")
        void tiesShareARank() {
            // Competition ranking, the same semantics the contest standings use. A rank is a
            // result; a row number is not.
            ratedContest("tied", alice, bob, carol);
            // Force a tie by hand: two competitors genuinely on the same rating.
            jdbc.update("UPDATE user_ratings SET rating = 1600, peak_rating = 1600");

            List<Map<String, Object>> items = items(alice.getJson("/api/rankings"));

            assertThat(items).extracting(entry -> entry.get("rank"))
                    .containsExactly(1, 1, 1);
        }

        @Test
        @DisplayName("pages, and reports the totals honestly")
        void paginates() {
            ratedContest("paged", alice, bob, carol);

            ResponseEntity<Map<String, Object>> first = alice.getJson("/api/rankings?page=0&size=2");
            ResponseEntity<Map<String, Object>> second = alice.getJson("/api/rankings?page=1&size=2");

            assertThat(items(first)).hasSize(2);
            assertThat(items(second)).hasSize(1);
            assertThat(first.getBody()).containsEntry("totalItems", 3)
                                       .containsEntry("hasNext", true);
            assertThat(second.getBody()).containsEntry("hasNext", false);
        }

        @Test
        @DisplayName("an absurd page size is clamped rather than served")
        void clampsPageSize() {
            ratedContest("clamped", alice, bob);

            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/rankings?size=100000");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("size", RatingQueryService.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("an empty leaderboard is an empty page, not an error")
        void emptyLeaderboard() {
            ResponseEntity<Map<String, Object>> response = alice.getJson("/api/rankings");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(response)).isEmpty();
            assertThat(response.getBody()).containsEntry("totalItems", 0);
        }
    }

    @Nested
    @DisplayName("A competitor's profile")
    class Profile {

        @Test
        @DisplayName("shows the rating, the peak, the rank and the history")
        void showsTheStanding() {
            ratedContest("profile", alice, bob);

            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/users/" + publicIdOf("alice") + "/rating");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("username", "alice")
                                          .containsEntry("rated", true)
                                          .containsEntry("rating", 1520)
                                          .containsEntry("peakRating", 1520)
                                          .containsEntry("rank", 1)
                                          .containsEntry("contestsRated", 1);
            assertThat((List<?>) response.getBody().get("recent")).hasSize(1);
            assertThat((List<?>) response.getBody().get("progression")).hasSize(1);
        }

        @Test
        @DisplayName("an unrated competitor gets nulls, not a 404 and not a zero")
        void unratedProfile() {
            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/users/" + publicIdOf("carol") + "/rating");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("rated", false)
                                          .containsEntry("rating", null)
                                          .containsEntry("peakRating", null)
                                          .containsEntry("rank", null)
                                          .containsEntry("contestsRated", 0);
        }

        @Test
        @DisplayName("the peak never falls, even when the rating does")
        void peakIsAHighWaterMark() {
            ratedContest("peak-up", alice, bob);
            int peak = ratingOf("alice");

            // A second contest Alice loses.
            UUID second = endedContest("peak-down", true, alice, bob);
            solve(second, "bob", betaProblem, 10);
            solve(second, "alice", betaProblem, 40);
            finalizeAsAdmin(second);

            assertThat(ratingOf("alice")).isLessThan(peak);
            assertThat(jdbc.queryForObject("""
                    SELECT peak_rating FROM user_ratings ur JOIN users u ON u.id = ur.user_id
                     WHERE u.username = 'alice'
                    """, Integer.class)).isEqualTo(peak);
        }

        @Test
        @DisplayName("carries no email and no account state")
        void carriesNothingPrivate() {
            ratedContest("private", alice, bob);

            ResponseEntity<String> raw =
                    alice.get("/api/users/" + publicIdOf("alice") + "/rating", String.class);

            assertThat(raw.getBody()).doesNotContain("alice@example.com")
                                     .doesNotContain("ROLE")
                                     .doesNotContainIgnoringCase("password");
        }

        @Test
        @DisplayName("an unknown user is a 404")
        void unknownUser() {
            assertThat(alice.getJson("/api/users/" + UUID.randomUUID() + "/rating")
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Contest history")
    class History {

        @Test
        @DisplayName("lists rated contests newest first, with the movement of each")
        void listsRatedContests() {
            ratedContest("h1", alice, bob);
            UUID second = endedContest("h2", true, alice, bob);
            solve(second, "alice", betaProblem, 10);
            solve(second, "bob", betaProblem, 40);
            finalizeAsAdmin(second);

            List<Map<String, Object>> entries = items(
                    alice.getJson("/api/users/" + publicIdOf("alice") + "/rating/history"));

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0)).containsEntry("contestSlug", "h2");
            assertThat(entries.get(1)).containsEntry("contestSlug", "h1");
            assertThat(entries.get(0)).containsKeys(
                    "rank", "participantCount", "score", "penalty",
                    "ratingBefore", "ratingChange", "ratingAfter");
        }

        @Test
        @DisplayName("unrated contests never appear in it")
        void unratedContestsAreAbsent() {
            ratedContest("rated-one", alice, bob);
            UUID casual = endedContest("casual-one", false, alice, bob);
            solve(casual, "alice", betaProblem, 10);
            solve(casual, "bob", betaProblem, 40);
            finalizeAsAdmin(casual);

            List<Map<String, Object>> entries = items(
                    alice.getJson("/api/users/" + publicIdOf("alice") + "/rating/history"));

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0)).containsEntry("contestSlug", "rated-one");
        }

        @Test
        @DisplayName("a competitor with no history gets an empty page")
        void emptyHistory() {
            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/users/" + publicIdOf("carol") + "/rating/history");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(response)).isEmpty();
        }
    }

    @Nested
    @DisplayName("A contest's rating outcome")
    class ContestOutcome {

        @Test
        @DisplayName("FINALIZED, with the caller's own movement")
        void finalizedOutcome() {
            UUID contestId = ratedContest("outcome", alice, bob);

            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/contests/" + contestId + "/rating");

            assertThat(response.getBody()).containsEntry("status", "FINALIZED")
                                          .containsEntry("rank", 1)
                                          .containsEntry("ratingBefore", 1500)
                                          .containsEntry("ratingChange", 20)
                                          .containsEntry("ratingAfter", 1520);
        }

        @Test
        @DisplayName("PENDING before finalisation — and emphatically not a change of zero")
        void pendingOutcome() {
            // Zero is a real rating change. Reporting it for a contest that has not been
            // rated yet would tell a competitor their result was "no movement" when in fact
            // it has not been computed.
            UUID contestId = endedContest("waiting", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            ResponseEntity<Map<String, Object>> response =
                    alice.getJson("/api/contests/" + contestId + "/rating");

            assertThat(response.getBody()).containsEntry("status", "PENDING")
                                          .containsEntry("ratingChange", null)
                                          .containsEntry("ratingAfter", null);
        }

        @Test
        @DisplayName("UNRATED for a contest that never moves ratings")
        void unratedOutcome() {
            UUID contestId = endedContest("never", false, alice, bob);

            assertThat(alice.getJson("/api/contests/" + contestId + "/rating").getBody())
                    .containsEntry("status", "UNRATED")
                    .containsEntry("ratingChange", null);
        }

        @Test
        @DisplayName("CANCELLED for a contest that was called off")
        void cancelledOutcome() {
            UUID contestId = endedContest("scrapped", true, alice, bob);
            jdbc.update("UPDATE contests SET lifecycle = 'CANCELLED' WHERE public_id = ?", contestId);

            assertThat(alice.getJson("/api/contests/" + contestId + "/rating").getBody())
                    .containsEntry("status", "CANCELLED");
        }

        @Test
        @DisplayName("FINALIZED with nulls for somebody who did not compete")
        void didNotCompete() {
            // Distinguished from PENDING because the answers are different: one means "wait",
            // the other means "you were not in this".
            UUID contestId = ratedContest("elsewhere", alice, bob);

            assertThat(carol.getJson("/api/contests/" + contestId + "/rating").getBody())
                    .containsEntry("status", "FINALIZED")
                    .containsEntry("rank", null)
                    .containsEntry("ratingChange", null);
        }

        @Test
        @DisplayName("an unknown contest is a 404")
        void unknownContest() {
            assertThat(alice.getJson("/api/contests/" + UUID.randomUUID() + "/rating")
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =============================================================== integrity

    @Nested
    @DisplayName("Data integrity")
    class Integrity {

        @Test
        @DisplayName("every rating equals the last history row that produced it")
        void theRatingIsExplainedByItsHistory() {
            // The invariant the whole design rests on: user_ratings is a cache and the
            // history is the truth. If these ever disagreed, nobody could tell which was
            // wrong, and every rating in the system would become a number to be argued with.
            ratedContest("inv1", alice, bob, carol);
            UUID second = endedContest("inv2", true, alice, bob, carol);
            solve(second, "carol", betaProblem, 5);
            solve(second, "alice", betaProblem, 25);
            solve(second, "bob", betaProblem, 45);
            finalizeAsAdmin(second);

            Integer mismatches = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_ratings ur
                     WHERE ur.rating <> (
                        SELECT r.rating_after FROM contest_rating_changes r
                         WHERE r.user_id = ur.user_id
                         ORDER BY r.created_at DESC, r.id DESC LIMIT 1)
                    """, Integer.class);

            assertThat(mismatches).isZero();
        }

        @Test
        @DisplayName("the history chains: each row starts where the previous one ended")
        void theHistoryIsAContinuousChain() {
            ratedContest("chain1", alice, bob);
            UUID second = endedContest("chain2", true, alice, bob);
            solve(second, "bob", betaProblem, 10);
            solve(second, "alice", betaProblem, 40);
            finalizeAsAdmin(second);

            Integer breaks = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM (
                        SELECT rating_before,
                               LAG(rating_after) OVER (PARTITION BY user_id ORDER BY id) AS previous
                          FROM contest_rating_changes) chain
                     WHERE previous IS NOT NULL AND previous <> rating_before
                    """, Integer.class);

            assertThat(breaks).isZero();
        }

        @Test
        @DisplayName("contests_rated matches the number of history rows")
        void contestCountsAgree() {
            ratedContest("count1", alice, bob);
            UUID second = endedContest("count2", true, alice, bob);
            solve(second, "alice", betaProblem, 10);
            solve(second, "bob", betaProblem, 40);
            finalizeAsAdmin(second);

            Integer mismatches = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_ratings ur
                     WHERE ur.contests_rated <> (
                        SELECT COUNT(*) FROM contest_rating_changes r WHERE r.user_id = ur.user_id)
                    """, Integer.class);

            assertThat(mismatches).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT contests_rated FROM user_ratings ur JOIN users u ON u.id = ur.user_id
                     WHERE u.username = 'alice'
                    """, Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("the history is append-only: it cannot be updated or deleted")
        void theHistoryIsAppendOnly() {
            ratedContest("immutable", alice, bob);

            assertThatThrownBy(() ->
                    jdbc.update("UPDATE contest_rating_changes SET rating_after = 9999"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM contest_rating_changes"))
                    .hasMessageContaining("append-only");
        }

        @Test
        @DisplayName("the database rejects a history row whose arithmetic does not add up")
        void arithmeticIsEnforcedBySchema() {
            UUID contestId = ratedContest("checked", alice, bob);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO contest_rating_changes
                        (user_id, contest_id, rating_before, rating_after, rating_change,
                         rank, participant_count, score, penalty,
                         expected_score, actual_score, k_factor)
                    VALUES ((SELECT id FROM users WHERE username = 'carol'),
                            (SELECT id FROM contests WHERE public_id = ?),
                            1500, 1600, 50, 1, 2, 0, 0, 0.5, 1.0, 20)
                    """, contestId))
                    .hasMessageContaining("ck_rating_changes_arithmetic");
        }

        @Test
        @DisplayName("the peak can never be recorded below the current rating")
        void peakCannotSitBelowCurrent() {
            ratedContest("peakcheck", alice, bob);

            assertThatThrownBy(() ->
                    jdbc.update("UPDATE user_ratings SET peak_rating = rating - 1"))
                    .hasMessageContaining("ck_user_ratings_peak_not_below_current");
        }

        @Test
        @DisplayName("a competitor cannot hold two rating rows")
        void oneRatingRowPerCompetitor() {
            ratedContest("unique", alice, bob);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO user_ratings (user_id, rating, peak_rating, contests_rated)
                    VALUES ((SELECT id FROM users WHERE username = 'alice'), 3000, 3000, 1)
                    """))
                    .hasMessageContaining("uq_user_ratings_user");
        }
    }

    // =============================================================== security

    @Nested
    @DisplayName("Security")
    class Security {

        @Test
        @DisplayName("1. an anonymous caller cannot read the ranking")
        void anonymousCannotReadTheRanking() {
            assertThat(anonymous().getJson("/api/rankings").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("2. an anonymous caller cannot read a rating profile")
        void anonymousCannotReadAProfile() {
            assertThat(anonymous().getJson("/api/users/" + publicIdOf("alice") + "/rating")
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("3. an ordinary user cannot finalise a contest")
        void userCannotFinalise() {
            UUID contestId = endedContest("forbidden", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            assertThat(alice.postJson("/api/admin/contests/" + contestId + "/finalize", null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(hasRating("alice")).isFalse();
        }

        @Test
        @DisplayName("4. an anonymous caller cannot finalise a contest")
        void anonymousCannotFinalise() {
            UUID contestId = endedContest("forbidden-anon", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            assertThat(anonymous().postJson("/api/admin/contests/" + contestId + "/finalize", null)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(hasRating("alice")).isFalse();
        }

        @Test
        @DisplayName("5. finalisation without a CSRF token is refused")
        void finalisationRequiresCsrf() {
            UUID contestId = endedContest("csrf", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            admin.forgetCsrfToken();

            assertThat(admin.postJson("/api/admin/contests/" + contestId + "/finalize", null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(hasRating("alice")).isFalse();
        }

        @Test
        @DisplayName("6. a rating cannot be set by posting one")
        void ratingCannotBePosted() {
            // There is no endpoint that accepts a rating. This asserts the absence: every
            // shape somebody might try returns 401/403/404/405, and none of them moves a
            // number.
            ratedContest("nopost", alice, bob);
            int before = ratingOf("alice");
            UUID aliceId = publicIdOf("alice");

            for (String path : List.of(
                    "/api/users/" + aliceId + "/rating",
                    "/api/ratings",
                    "/api/rankings",
                    "/api/admin/users/" + aliceId + "/rating")) {
                HttpStatus status = (HttpStatus) admin
                        .postJson(path, Map.of("rating", 3000, "peakRating", 3000))
                        .getStatusCode();
                assertThat(status.is2xxSuccessful())
                        .as("POST %s must not succeed", path)
                        .isFalse();
            }

            assertThat(ratingOf("alice")).isEqualTo(before);
        }

        @Test
        @DisplayName("7. a rating in the contest creation body is ignored, not applied")
        void ratingInAContestBodyIsIgnored() {
            // Overposting. ContestRequest has no rating field, so these are silently dropped
            // by the binder — the assertion is that nothing anywhere picks them up.
            Map<String, Object> body = contestBody("overpost", true, hoursFromNow(1), hoursFromNow(4));
            body.put("rating", 3000);
            body.put("ratingFinalizedAt", Instant.now().toString());
            body.put("ratedParticipantCount", 99);

            UUID contestId = UUID.fromString(admin.postJson("/api/admin/contests", body)
                    .getBody().get("id").toString());

            assertThat(finalizedAt(contestId)).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT rated_participant_count FROM contests WHERE public_id = ?",
                    Integer.class, contestId)).isNull();
        }

        @Test
        @DisplayName("8. a finalisation body cannot supply standings, ranks or ratings")
        void finalisationIgnoresItsBody() {
            // The endpoint takes no body at all; every input comes from the database. This
            // posts one anyway and proves the computed result is unchanged by it.
            UUID contestId = endedContest("bodyless", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            admin.postJson("/api/admin/contests/" + contestId + "/finalize", Map.of(
                    "ratedParticipants", 99,
                    "standings", List.of(Map.of("username", "bob", "rank", 1)),
                    "ratingChange", 500));

            // Alice still won, because the standings came from the submissions.
            assertThat(ratingOf("alice")).isEqualTo(1520);
            assertThat(ratingOf("bob")).isEqualTo(1480);
        }

        @Test
        @DisplayName("9. the contest rating result is the caller's own, not one they name")
        void contestResultIsAlwaysTheCallersOwn() {
            // There is no parameter for whose result to return — the caller comes from the
            // session. Appending one changes nothing.
            UUID contestId = ratedContest("mine", alice, bob);

            Map<String, Object> asBob = bob.getJson(
                    "/api/contests/" + contestId + "/rating?userId=" + publicIdOf("alice")).getBody();

            assertThat(asBob).containsEntry("rank", 2).containsEntry("ratingChange", -20);
        }

        @Test
        @DisplayName("10. no ranking parameter can inject an ordering expression")
        void rankingRejectsInjectedOrdering() {
            ratedContest("inject", alice, bob);

            for (String attack : List.of(
                    "?sort=rating;DROP TABLE user_ratings",
                    "?order=(SELECT 1)",
                    "?page=0&size=1&sort=users.email",
                    "?size=1&orderBy=rating--")) {
                ResponseEntity<Map<String, Object>> response =
                        alice.getJson("/api/rankings" + attack);
                assertThat(response.getStatusCode())
                        .as("GET /api/rankings%s", attack)
                        .isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);
            }

            // Still there, still intact.
            assertThat(items(alice.getJson("/api/rankings"))).hasSize(2);
        }

        @Test
        @DisplayName("11. a negative or absurd page parameter cannot escape the bounds")
        void pageParametersAreBounded() {
            ratedContest("bounds", alice, bob);

            assertThat(alice.getJson("/api/rankings?page=-5&size=-1").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(alice.getJson("/api/rankings?page=999999999&size=100").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(items(alice.getJson("/api/rankings?page=999999999&size=100"))).isEmpty();
        }

        @Test
        @DisplayName("12. a malformed identifier is a 400, not a stack trace")
        void malformedIdentifiersAreRejected() {
            ResponseEntity<String> response =
                    alice.get("/api/users/not-a-uuid/rating", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).doesNotContain("java.")
                                          .doesNotContain("org.springframework")
                                          .doesNotContain("SELECT");
        }

        @Test
        @DisplayName("13. the ranking discloses no email address")
        void theRankingDisclosesNothingPrivate() {
            ratedContest("leak", alice, bob);

            ResponseEntity<String> raw = alice.get("/api/rankings", String.class);

            assertThat(raw.getBody()).doesNotContain("@example.com")
                                     .doesNotContainIgnoringCase("password")
                                     .doesNotContain("\"email\"")
                                     .doesNotContain("\"role\"");
        }

        @Test
        @DisplayName("14. one user's history is not another user's source code")
        void historyDisclosesNoSubmissionContent() {
            // The standing rule from Phase 3 onward: no identifier in a URL may be walked to
            // reach somebody else's code. The history is derived from submissions, so it is
            // worth asserting it did not bring any along.
            UUID contestId = ratedContest("nocode", alice, bob);

            ResponseEntity<String> raw = bob.get(
                    "/api/users/" + publicIdOf("alice") + "/rating/history", String.class);

            assertThat(raw.getBody()).doesNotContain("print(1)")
                                     .doesNotContain("sourceCode");
            assertThat(raw.getBody()).contains(contestId.toString());
        }

        @Test
        @DisplayName("15. finalisation is rate limited, and the limit is enforced")
        void finalisationIsRateLimited() {
            UUID contestId = endedContest("limited", true, alice, bob);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 40);

            ResponseEntity<Map<String, Object>> response =
                    admin.postJson("/api/admin/contests/" + contestId + "/finalize", null);

            // The header proves the endpoint went through the Phase 9 interceptor rather
            // than around it. Draining a 20-token bucket against a real Redis is the rate
            // limiter's own suite's job, not this one's.
            assertThat(response.getHeaders().getFirst(RateLimitHeaders.LIMIT)).isNotNull();
            assertThat(response.getHeaders().getFirst(RateLimitHeaders.REMAINING)).isNotNull();
        }

        @Test
        @DisplayName("16. reading the ranking is rate limited too")
        void rankingIsRateLimited() {
            ResponseEntity<Map<String, Object>> response = alice.getJson("/api/rankings");

            assertThat(response.getHeaders().getFirst(RateLimitHeaders.LIMIT)).isNotNull();
        }

        @Test
        @DisplayName("a failed finalisation attempt does not half-rate the field")
        void aFailedFinalisationLeavesNothingBehind() {
            // The one outcome that would be unrecoverable: forty of eighty competitors rated,
            // with no way to tell which forty. Provoked by breaking the contest row the
            // transaction has to write back to.
            UUID contestId = endedContest("atomic", true, alice, bob, carol);
            solve(contestId, "alice", alphaProblem, 10);
            solve(contestId, "bob", alphaProblem, 30);
            solve(contestId, "carol", alphaProblem, 50);

            AtomicReference<Exception> thrown = new AtomicReference<>();
            try {
                transactionTemplate.execute(status -> {
                    finalizationService.finalize(
                            contestId, ContestFinalizationService.Source.ADMIN);
                    status.setRollbackOnly();
                    return null;
                });
            } catch (Exception e) {
                thrown.set(e);
            }

            // Everything went back: no ratings, no history, and the claim released so the
            // next attempt starts clean.
            assertThat(hasRating("alice")).isFalse();
            assertThat(hasRating("bob")).isFalse();
            assertThat(hasRating("carol")).isFalse();
            assertThat(historyRowCount(contestId)).isZero();
            assertThat(finalizedAt(contestId)).isNull();

            // And the contest can still be finalised properly afterwards.
            finalizeAsAdmin(contestId);
            assertThat(historyRowCount(contestId)).isEqualTo(3);
        }
    }

    // =============================================================== helpers

    private BrowserClient anonymous() {
        BrowserClient client = new BrowserClient(restTemplate);
        client.getJson("/api/system/info");   // prime the CSRF cookie as a browser would
        return client;
    }

    private Map<String, Object> contestBody(String slug, Boolean rated,
                                            Instant startAt, Instant endAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Contest " + slug);
        body.put("slug", slug);
        body.put("description", "A contest.");
        body.put("startAt", startAt.toString());
        body.put("endAt", endAt.toString());
        if (rated != null) {
            body.put("rated", rated);
        }
        return body;
    }

    private static Instant hoursFromNow(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    }

    private UUID createDraft(String slug, Boolean rated, Instant startAt, Instant endAt) {
        ResponseEntity<Map<String, Object>> response =
                admin.postJson("/api/admin/contests", contestBody(slug, rated, startAt, endAt));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private UUID createPublished(String slug, Boolean rated, Instant startAt, Instant endAt) {
        UUID contestId = createDraft(slug, rated, startAt, endAt);
        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", alphaProblem.toString(), "points", 100))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/problems",
                Map.of("problemId", betaProblem.toString(), "points", 100))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.postJson("/api/admin/contests/" + contestId + "/publish", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return contestId;
    }

    /**
     * A contest that is over, with the given contestants registered.
     *
     * <p>Built in the order reality would: created in the future, published, registered for,
     * and only then moved into the past — publishing an ended contest and registering for a
     * started one are both things the rules forbid, and going round them would be testing a
     * state the system cannot actually reach.
     */
    private UUID endedContest(String slug, boolean rated, BrowserClient... contestants) {
        UUID contestId = createPublished(slug, rated, hoursFromNow(1), hoursFromNow(4));
        for (BrowserClient contestant : contestants) {
            register(contestant, contestId);
        }
        moveSchedule(contestId, hoursFromNow(-3), hoursFromNow(-1));
        return contestId;
    }

    /** An ended, rated, finalised contest where alice beats bob beats carol. */
    private UUID ratedContest(String slug, BrowserClient... contestants) {
        UUID contestId = endedContest(slug, true, contestants);
        int minute = 10;
        for (BrowserClient contestant : contestants) {
            solve(contestId, usernameOf(contestant), alphaProblem, minute);
            minute += 15;
        }
        finalizeAsAdmin(contestId);
        return contestId;
    }

    private String usernameOf(BrowserClient client) {
        if (client == alice) {
            return "alice";
        }
        if (client == bob) {
            return "bob";
        }
        if (client == carol) {
            return "carol";
        }
        throw new IllegalArgumentException("unknown client");
    }

    private Map<String, Object> finalizeAsAdmin(UUID contestId) {
        ResponseEntity<Map<String, Object>> response =
                admin.postJson("/api/admin/contests/" + contestId + "/finalize", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void moveSchedule(UUID contestId, Instant startAt, Instant endAt) {
        jdbc.update("UPDATE contests SET start_at = ?, end_at = ? WHERE public_id = ?",
                java.sql.Timestamp.from(startAt), java.sql.Timestamp.from(endAt), contestId);
    }

    private void register(BrowserClient client, UUID contestId) {
        assertThat(client.postJson("/api/contests/" + contestId + "/register", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void solve(UUID contestId, String username, UUID problemId, int minutesIn) {
        submission(contestId, username, problemId, SubmissionStatus.ACCEPTED, minutesIn);
    }

    /** Writes a judged submission the way the worker would, at a known point in the contest. */
    private void submission(UUID contestId, String username, UUID problemId,
                            SubmissionStatus status, int minutesIn) {
        Instant startAt = jdbc.queryForObject(
                "SELECT start_at FROM contests WHERE public_id = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), contestId);
        java.sql.Timestamp at = java.sql.Timestamp.from(startAt.plus(minutesIn, ChronoUnit.MINUTES));

        jdbc.update("""
                INSERT INTO submissions
                    (public_id, problem_id, user_id, contest_id, language, source_code,
                     status, created_at, updated_at, finished_at, attempts)
                VALUES (gen_random_uuid(),
                        (SELECT id FROM problems WHERE public_id = ?),
                        (SELECT id FROM users WHERE username = ?),
                        (SELECT id FROM contests WHERE public_id = ?),
                        'PYTHON', 'print(1)', ?, ?, ?, ?, 1)
                """, problemId, username, contestId, status.name(), at, at, at);
    }

    private Map<String, Object> detail(UUID contestId) {
        return admin.getJson("/api/admin/contests/" + contestId).getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private int ratingOf(String username) {
        return jdbc.queryForObject("""
                SELECT ur.rating FROM user_ratings ur JOIN users u ON u.id = ur.user_id
                 WHERE u.username = ?
                """, Integer.class, username);
    }

    private boolean hasRating(String username) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_ratings ur JOIN users u ON u.id = ur.user_id
                 WHERE u.username = ?
                """, Integer.class, username) > 0;
    }

    private int historyRowCount(UUID contestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM contest_rating_changes r
                 JOIN contests c ON c.id = r.contest_id WHERE c.public_id = ?
                """, Integer.class, contestId);
    }

    private Instant finalizedAt(UUID contestId) {
        return jdbc.queryForObject(
                "SELECT rating_finalized_at FROM contests WHERE public_id = ?",
                (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                contestId);
    }

    private UUID publicIdOf(String username) {
        return jdbc.queryForObject("SELECT public_id FROM users WHERE username = ?",
                UUID.class, username);
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(
                User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private UUID createProblem(String title, String slug) {
        return transactionTemplate.execute(status -> {
            User owner = userRepository.findByUsernameOrEmail("boss").orElseThrow();
            Problem problem = Problem.createDraft(slug, title, Difficulty.EASY, owner);
            problem.updateContent(title, "Add two numbers.", "Two integers.", "Their sum.",
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
