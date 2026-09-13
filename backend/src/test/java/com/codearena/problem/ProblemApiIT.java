package com.codearena.problem;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.problem.dto.ProblemRequest;
import com.codearena.support.BrowserClient;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Problem management over real HTTP, against real PostgreSQL and Redis.
 *
 * <p>Everything here goes through the full security filter chain with genuine session and
 * CSRF handling, because the questions this suite answers are authorisation questions and
 * a mocked security context would answer them incorrectly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "a properly long passphrase";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private BrowserClient admin;
    private BrowserClient member;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbcTemplate);

        createAccount("root", "root@example.com", Role.ADMIN);
        admin = signIn("root");

        createAccount("ada", "ada@example.com", Role.USER);
        member = signIn("ada");
    }

    // ------------------------------------------------------------------ authoring

    @Test
    void adminCreatesAProblemAsADraft() {
        ResponseEntity<Map> response = admin.post("/api/admin/problems", draftRequest("Two Sum", "two-sum"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("slug", "two-sum")
                .containsEntry("title", "Two Sum")
                .containsEntry("status", "DRAFT");
        assertThat(response.getBody().get("id").toString()).hasSize(36);
    }

    @Test
    void derivesTheSlugFromTheTitleWhenNoneIsSupplied() {
        ResponseEntity<Map> response = admin.post("/api/admin/problems",
                draftRequest("Longest Increasing Subsequence", null), Map.class);

        assertThat(response.getBody()).containsEntry("slug", "longest-increasing-subsequence");
    }

    @Test
    void rejectsADuplicateSlug() {
        admin.post("/api/admin/problems", draftRequest("Two Sum", "two-sum"), Map.class);

        ResponseEntity<Map> response = admin.post("/api/admin/problems",
                draftRequest("Another Problem", "two-sum"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "PROBLEM_SLUG_ALREADY_EXISTS");
    }

    /** The unique constraint, not just the application pre-check, must hold. */
    @Test
    void theDatabaseRefusesADuplicateSlugIndependentlyOfTheApplication() {
        admin.post("/api/admin/problems", draftRequest("Two Sum", "two-sum"), Map.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'problems' AND indexdef LIKE '%UNIQUE%slug%'",
                Integer.class);
        assertThat(count).isPositive();
    }

    @Test
    void rejectsAMalformedSlugAndABlankTitle() {
        ResponseEntity<Map> response = admin.post("/api/admin/problems",
                new ProblemRequest("   ", "Not A Slug", null, null, null, null, null,
                        Difficulty.EASY, null, null, null, null, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
    }

    @Test
    void updatesContentWithoutChangingTheSlug() {
        String id = createDraft("Two Sum", "two-sum");

        ResponseEntity<Map> response = admin.put("/api/admin/problems/" + id,
                new ProblemRequest("Renamed Title", null, "s", "i", "o", "c", null,
                        Difficulty.HARD, Set.of(ProblemTag.ARRAY), 2000, 512, null, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("title", "Renamed Title")
                .containsEntry("difficulty", "HARD")
                .as("renaming must not silently change the URL handle")
                .containsEntry("slug", "two-sum");
    }

    /**
     * Omitting a field must not destroy it. A client that sends only the title to fix a
     * typo must not thereby delete the statement, the examples and every test case — which
     * strict "PUT replaces the resource" semantics would do.
     */
    @Test
    void anUpdateThatOmitsFieldsLeavesThemIntact() {
        String id = createPublishableDraft("Two Sum", "two-sum");

        admin.put("/api/admin/problems/" + id,
                new ProblemRequest("Renamed Only", null, null, null, null, null, null,
                        Difficulty.EASY, null, null, null, null, null), Map.class);

        ResponseEntity<Map> after = admin.get("/api/admin/problems/" + id, Map.class);
        assertThat(after.getBody())
                .containsEntry("title", "Renamed Only")
                .containsEntry("statement", "Add the two numbers.")
                .containsEntry("inputFormat", "Two integers.");
        assertThat((List<?>) after.getBody().get("examples")).hasSize(2);
        assertThat((List<?>) after.getBody().get("testCases")).hasSize(1);
        assertThat((List<?>) after.getBody().get("missingForPublication")).isEmpty();
    }

    /** An explicitly empty value does clear, which is how a field is actually removed. */
    @Test
    void anExplicitlyEmptyValueClearsTheField() {
        String id = createPublishableDraft("Two Sum", "two-sum");

        admin.put("/api/admin/problems/" + id,
                new ProblemRequest("Two Sum", null, "", null, null, null, null,
                        Difficulty.EASY, null, null, null, List.of(), List.of()), Map.class);

        ResponseEntity<Map> after = admin.get("/api/admin/problems/" + id, Map.class);
        assertThat((List<?>) after.getBody().get("examples")).isEmpty();
        assertThat((List<?>) after.getBody().get("testCases")).isEmpty();
        assertThat((List<String>) after.getBody().get("missingForPublication"))
                .contains("statement", "at least one example", "at least one test case");
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void refusesToPublishAnIncompleteProblemAndSaysWhy() {
        String id = createDraft("Two Sum", "two-sum");

        ResponseEntity<Map> response = admin.post("/api/admin/problems/" + id + "/publish", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
        assertThat(response.getBody().toString()).contains("at least one test case");
    }

    @Test
    void publishesACompleteProblem() {
        String id = createPublishableDraft("Two Sum", "two-sum");

        ResponseEntity<Map> response = admin.post("/api/admin/problems/" + id + "/publish", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "PUBLISHED");
    }

    @Test
    void movesThroughTheWholeLifecycle() {
        String id = createPublishableDraft("Two Sum", "two-sum");

        assertThat(statusAfter(id, "publish")).isEqualTo("PUBLISHED");
        assertThat(statusAfter(id, "unpublish")).isEqualTo("DRAFT");
        assertThat(statusAfter(id, "archive")).isEqualTo("ARCHIVED");
        assertThat(statusAfter(id, "restore")).isEqualTo("DRAFT");
    }

    @Test
    void refusesAnIllegalTransition() {
        String id = createPublishableDraft("Two Sum", "two-sum");
        admin.post("/api/admin/problems/" + id + "/archive", null, Map.class);

        ResponseEntity<Map> response = admin.post("/api/admin/problems/" + id + "/publish", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "INVALID_STATUS_TRANSITION");
    }

    /**
     * Status is not a writable property. Even if a client invents one in the payload, the
     * request record has no such field, so it is ignored and the problem stays a draft.
     */
    @Test
    void cannotBePublishedByOverpostingAStatusField() {
        String id = createDraft("Two Sum", "two-sum");

        Map<String, Object> payload = Map.of(
                "title", "Two Sum",
                "difficulty", "EASY",
                "status", "PUBLISHED");
        admin.put("/api/admin/problems/" + id, payload, Map.class);

        ResponseEntity<Map> after = admin.get("/api/admin/problems/" + id, Map.class);
        assertThat(after.getBody())
                .as("overposting a status must not publish anything")
                .containsEntry("status", "DRAFT");
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void usersSeeOnlyPublishedProblems() {
        publish(createPublishableDraft("Published One", "published-one"));
        createDraft("Hidden Draft", "hidden-draft");
        String archived = createPublishableDraft("Archived One", "archived-one");
        publish(archived);
        admin.post("/api/admin/problems/" + archived + "/archive", null, Map.class);

        ResponseEntity<Map> response = member.get("/api/problems", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("slug", "published-one");
        assertThat(response.getBody()).containsEntry("totalItems", 1);
    }

    @Test
    void aDraftIsNotFoundRatherThanForbiddenForANormalUser() {
        createDraft("Hidden Draft", "hidden-draft");

        ResponseEntity<Map> response = member.get("/api/problems/hidden-draft", Map.class);

        assertThat(response.getStatusCode())
                .as("403 would confirm the problem exists; 404 withholds that")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "PROBLEM_NOT_FOUND");
    }

    @Test
    void anArchivedProblemIsAlsoHiddenFromTheDetailEndpoint() {
        String id = createPublishableDraft("Archived One", "archived-one");
        publish(id);
        admin.post("/api/admin/problems/" + id + "/archive", null, Map.class);

        assertThat(member.get("/api/problems/archived-one", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The single most important assertion in this phase. */
    @Test
    void theUserFacingDetailNeverLeaksTestCases() {
        String id = createPublishableDraft("Two Sum", "two-sum");
        publish(id);

        ResponseEntity<String> raw = member.get("/api/problems/two-sum", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody())
                .contains("Two Sum")
                .doesNotContain("testCases")
                .doesNotContain("expectedOutput")
                .as("the hidden test case's answer key must never appear")
                .doesNotContain("SECRET-ANSWER")
                .doesNotContain("SECRET-INPUT");
    }

    @Test
    void theAdminViewDoesIncludeTestCases() {
        String id = createPublishableDraft("Two Sum", "two-sum");

        ResponseEntity<String> raw = admin.get("/api/admin/problems/" + id, String.class);

        assertThat(raw.getBody()).contains("SECRET-ANSWER").contains("testCases");
    }

    @Test
    void detailReturnsExamplesInOrder() {
        String id = createPublishableDraft("Two Sum", "two-sum");
        publish(id);

        ResponseEntity<Map> response = member.get("/api/problems/two-sum", Map.class);

        List<Map<String, Object>> examples = (List<Map<String, Object>>) response.getBody().get("examples");
        assertThat(examples).hasSize(2);
        assertThat(examples).extracting(e -> e.get("position")).containsExactly(0, 1);
        assertThat(examples).extracting(e -> e.get("input")).containsExactly("1 2", "3 4");
    }

    // ------------------------------------------------- pagination and filtering

    @Test
    void paginatesDeterministically() {
        for (int i = 1; i <= 5; i++) {
            publish(createPublishableDraft("Problem " + i, "problem-" + i));
        }

        ResponseEntity<Map> first = member.get("/api/problems?page=0&size=2&sort=OLDEST", Map.class);
        ResponseEntity<Map> second = member.get("/api/problems?page=1&size=2&sort=OLDEST", Map.class);

        assertThat(first.getBody()).containsEntry("totalItems", 5).containsEntry("totalPages", 3)
                .containsEntry("hasNext", true).containsEntry("hasPrevious", false);
        assertThat(slugsOf(first)).containsExactly("problem-1", "problem-2");
        assertThat(slugsOf(second)).containsExactly("problem-3", "problem-4");
    }

    @Test
    void clampsAnOversizedPageSize() {
        publish(createPublishableDraft("Two Sum", "two-sum"));

        ResponseEntity<Map> response = member.get("/api/problems?size=99999", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("size", ProblemPageRequest.MAX_SIZE);
    }

    @Test
    void rejectsNonsensicalPaginationAndUnknownSorts() {
        assertThat(member.get("/api/problems?page=-1", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(member.get("/api/problems?size=0", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(member.get("/api/problems?sort=createdBy.passwordHash", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filtersByDifficultyTagAndSearch() {
        publish(createPublishableDraft("Easy Arrays", "easy-arrays", Difficulty.EASY, Set.of(ProblemTag.ARRAY)));
        publish(createPublishableDraft("Hard Graphs", "hard-graphs", Difficulty.HARD, Set.of(ProblemTag.GRAPH)));

        assertThat(slugsOf(member.get("/api/problems?difficulty=EASY", Map.class)))
                .containsExactly("easy-arrays");
        assertThat(slugsOf(member.get("/api/problems?tag=GRAPH", Map.class)))
                .containsExactly("hard-graphs");
        assertThat(slugsOf(member.get("/api/problems?search=graph", Map.class)))
                .containsExactly("hard-graphs");
        assertThat(slugsOf(member.get("/api/problems?search=GRAPH", Map.class)))
                .as("search must be case-insensitive")
                .containsExactly("hard-graphs");
    }

    /** An unrecognised enum is the client's mistake, not a server fault. */
    @Test
    void reportsAnUnknownDifficultyAsABadRequestNotAServerError() {
        ResponseEntity<Map> response = member.get("/api/problems?difficulty=IMPOSSIBLE", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
    }

    /** A tag filter must not multiply rows when a problem carries several tags. */
    @Test
    void countsAProblemOnceEvenWhenItCarriesManyTags() {
        publish(createPublishableDraft("Multi Tagged", "multi-tagged", Difficulty.EASY,
                Set.of(ProblemTag.ARRAY, ProblemTag.HASHING, ProblemTag.SORTING)));

        ResponseEntity<Map> response = member.get("/api/problems?tag=ARRAY", Map.class);

        assertThat(response.getBody()).containsEntry("totalItems", 1);
        assertThat(slugsOf(response)).containsExactly("multi-tagged");
    }

    // -------------------------------------------------------------- authorization

    @Test
    void normalUsersCannotReachAnyAdminProblemEndpoint() {
        String id = createDraft("Two Sum", "two-sum");

        assertThat(member.get("/api/admin/problems", Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(member.get("/api/admin/problems/" + id, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(member.post("/api/admin/problems", draftRequest("X", "x"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(member.put("/api/admin/problems/" + id, draftRequest("X", "x"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(member.post("/api/admin/problems/" + id + "/publish", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(member.post("/api/admin/problems/" + id + "/archive", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousCallersAreRejectedFromBothCatalogueAndAdmin() {
        publish(createPublishableDraft("Two Sum", "two-sum"));
        BrowserClient anonymous = new BrowserClient(restTemplate);
        anonymous.get("/api/system/info", Map.class);

        assertThat(anonymous.get("/api/problems", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.get("/api/problems/two-sum", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.get("/api/admin/problems", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A state-changing admin call still needs its CSRF token. */
    @Test
    void adminMutationsRequireTheCsrfToken() {
        admin.forgetCsrfToken();

        ResponseEntity<Map> response = admin.post("/api/admin/problems", draftRequest("X", "x"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnknownProblemIdIsNotFound() {
        ResponseEntity<Map> response = admin.get(
                "/api/admin/problems/00000000-0000-0000-0000-000000000000", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "PROBLEM_NOT_FOUND");
    }

    // ------------------------------------------------------------- admin listing

    @Test
    void theAdminListingShowsEveryStatusAndCanFilterByIt() {
        publish(createPublishableDraft("Published One", "published-one"));
        createDraft("Hidden Draft", "hidden-draft");

        ResponseEntity<Map> all = admin.get("/api/admin/problems", Map.class);
        assertThat(all.getBody()).containsEntry("totalItems", 2);

        ResponseEntity<Map> drafts = admin.get("/api/admin/problems?status=DRAFT", Map.class);
        assertThat(slugsOf(drafts)).containsExactly("hidden-draft");

        ResponseEntity<Map> published = admin.get("/api/admin/problems?status=PUBLISHED", Map.class);
        assertThat(slugsOf(published)).containsExactly("published-one");
    }

    @Test
    void recordsWhoCreatedAndLastUpdatedTheProblem() {
        String id = createDraft("Two Sum", "two-sum");
        admin.put("/api/admin/problems/" + id,
                new ProblemRequest("Two Sum", null, "s", "i", "o", "c", null,
                        Difficulty.EASY, null, null, null, null, null), Map.class);

        ResponseEntity<Map> response = admin.get("/api/admin/problems/" + id, Map.class);

        assertThat(response.getBody())
                .containsEntry("createdBy", "root")
                .containsEntry("updatedBy", "root");
    }

    // -------------------------------------------------------------------- helpers

    private ProblemRequest draftRequest(String title, String slug) {
        return new ProblemRequest(title, slug, null, null, null, null, null,
                Difficulty.EASY, null, null, null, null, null);
    }

    private String createDraft(String title, String slug) {
        ResponseEntity<Map> response = admin.post("/api/admin/problems", draftRequest(title, slug), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private String createPublishableDraft(String title, String slug) {
        return createPublishableDraft(title, slug, Difficulty.EASY, Set.of(ProblemTag.ARRAY));
    }

    private String createPublishableDraft(String title, String slug, Difficulty difficulty, Set<ProblemTag> tags) {
        ProblemRequest request = new ProblemRequest(
                title, slug,
                "Add the two numbers.", "Two integers.", "Their sum.", "1 <= a, b <= 100", null,
                difficulty, tags, 1000, 256,
                List.of(new ProblemRequest.ExampleRequest("1 2", "3", "adds them"),
                        new ProblemRequest.ExampleRequest("3 4", "7", null)),
                List.of(new ProblemRequest.TestCaseRequest("SECRET-INPUT", "SECRET-ANSWER", true, 1)));

        ResponseEntity<Map> response = admin.post("/api/admin/problems", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private void publish(String id) {
        ResponseEntity<Map> response = admin.post("/api/admin/problems/" + id + "/publish", null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String statusAfter(String id, String action) {
        ResponseEntity<Map> response = admin.post("/api/admin/problems/" + id + "/" + action, null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("status").toString();
    }

    private List<String> slugsOf(ResponseEntity<Map> response) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        return items.stream().map(item -> item.get("slug").toString()).toList();
    }

    private void createAccount(String username, String email, Role role) {
        userRepository.saveAndFlush(User.create(username, email, passwordEncoder.encode(PASSWORD), role));
    }

    private BrowserClient signIn(String username) {
        BrowserClient client = new BrowserClient(restTemplate);
        client.get("/api/system/info", Map.class);   // delivers the CSRF cookie
        ResponseEntity<Map> response = client.post("/api/auth/login",
                new LoginRequest(username, PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return client;
    }
}
