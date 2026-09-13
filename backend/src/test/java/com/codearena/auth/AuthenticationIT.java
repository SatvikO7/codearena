package com.codearena.auth;

import com.codearena.AbstractIntegrationTest;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.user.Role;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.codearena.support.BrowserClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end authentication over real HTTP, against real PostgreSQL and Redis.
 *
 * <p>Every request travels through the full security filter chain with genuine cookie
 * and CSRF handling (see {@link BrowserClient}), so these tests fail if the session is
 * not really established, if the CSRF cookie is never issued, or if logout leaves a
 * usable session behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationIT extends AbstractIntegrationTest {

    private static final String STRONG_PASSWORD = "a properly long passphrase";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private BrowserClient browser;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbcTemplate);
        browser = new BrowserClient(restTemplate);
        // A browser loading the SPA issues a GET before it can POST anything: that is the
        // request which delivers the CSRF cookie. Priming here reproduces that order
        // rather than pretending a client can POST with a token it was never given.
        browser.get("/api/system/info", Map.class);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void registersAnAccountAndNeverReturnsThePassword() {
        ResponseEntity<Map> response = browser.post("/api/auth/register",
                new RegistrationRequest("ada", "ada@example.com", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("username", "ada")
                .containsEntry("email", "ada@example.com")
                .containsEntry("role", "USER")
                .containsEntry("enabled", true)
                .containsKey("id")
                .doesNotContainKeys("password", "passwordHash");

        // The exposed identifier is the UUID public id, never the sequential primary key.
        assertThat(response.getBody().get("id").toString()).hasSize(36);
    }

    @Test
    void storesOnlyAHashOfThePassword() {
        browser.post("/api/auth/register",
                new RegistrationRequest("ada", "ada@example.com", STRONG_PASSWORD), Map.class);

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE username = 'ada'", String.class);

        assertThat(storedHash).isNotNull().doesNotContain(STRONG_PASSWORD).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(STRONG_PASSWORD, storedHash)).isTrue();
    }

    @Test
    void rejectsADuplicateUsername() {
        register("ada", "ada@example.com");

        ResponseEntity<Map> response = browser.post("/api/auth/register",
                new RegistrationRequest("ada", "different@example.com", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "USERNAME_ALREADY_TAKEN");
    }

    /** The unique indexes over lower(...) make this a database guarantee, not an app one. */
    @Test
    void treatsUsernamesAndEmailsAsCaseInsensitiveForUniqueness() {
        register("ada", "ada@example.com");

        ResponseEntity<Map> sameUsername = browser.post("/api/auth/register",
                new RegistrationRequest("ADA", "other@example.com", STRONG_PASSWORD), Map.class);
        assertThat(sameUsername.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameUsername.getBody()).containsEntry("error", "USERNAME_ALREADY_TAKEN");

        ResponseEntity<Map> sameEmail = browser.post("/api/auth/register",
                new RegistrationRequest("grace", "Ada@Example.COM", STRONG_PASSWORD), Map.class);
        assertThat(sameEmail.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameEmail.getBody()).containsEntry("error", "EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void rejectsAWeakPasswordWithAFieldLevelError() {
        ResponseEntity<Map> response = browser.post("/api/auth/register",
                new RegistrationRequest("ada", "ada@example.com", "short"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
        assertThat((List<?>) response.getBody().get("fieldErrors")).isNotEmpty();
    }

    @Test
    void rejectsAMalformedUsernameAndEmail() {
        ResponseEntity<Map> response = browser.post("/api/auth/register",
                new RegistrationRequest("has spaces!", "not-an-email", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_FAILED");
        assertThat((List<?>) response.getBody().get("fieldErrors")).hasSize(2);
    }

    // ---------------------------------------------------------------------- login

    @Test
    void logsInWithUsernameAndEstablishesASession() {
        register("ada", "ada@example.com");

        ResponseEntity<Map> response = browser.post("/api/auth/login",
                new LoginRequest("ada", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("username", "ada");
        assertThat(browser.hasCookie("CODEARENA_SESSION")).isTrue();
    }

    @Test
    void logsInWithTheEmailAddressToo() {
        register("ada", "ada@example.com");

        ResponseEntity<Map> response = browser.post("/api/auth/login",
                new LoginRequest("ada@example.com", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A wrong password and an account that does not exist must be indistinguishable, or
     * login becomes a tool for discovering which usernames are registered.
     */
    @Test
    void givesIdenticalAnswersForAWrongPasswordAndAnUnknownAccount() {
        register("ada", "ada@example.com");

        ResponseEntity<Map> wrongPassword = browser.post("/api/auth/login",
                new LoginRequest("ada", "the wrong passphrase"), Map.class);
        ResponseEntity<Map> unknownUser = browser.post("/api/auth/login",
                new LoginRequest("nobody", "the wrong passphrase"), Map.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).containsEntry("error", "INVALID_CREDENTIALS");
        assertThat(unknownUser.getBody().get("message"))
                .isEqualTo(wrongPassword.getBody().get("message"));
    }

    @Test
    void refusesToSignInADisabledAccount() {
        register("ada", "ada@example.com");
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE username = 'ada'");

        ResponseEntity<Map> response = browser.post("/api/auth/login",
                new LoginRequest("ada", STRONG_PASSWORD), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "ACCOUNT_DISABLED");
    }

    // ----------------------------------------------------------------- current user

    @Test
    void refusesTheCurrentUserEndpointWithoutASession() {
        ResponseEntity<Map> response = browser.get("/api/auth/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .containsEntry("error", "AUTHENTICATION_REQUIRED")
                .containsEntry("path", "/api/auth/me");
    }

    @Test
    void returnsTheAuthenticatedAccount() {
        register("ada", "ada@example.com");
        login("ada");

        ResponseEntity<Map> response = browser.get("/api/auth/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("username", "ada")
                .containsEntry("role", "USER")
                .doesNotContainKeys("password", "passwordHash");
    }

    // ---------------------------------------------------------------------- logout

    @Test
    void logoutEndsTheSession() {
        register("ada", "ada@example.com");
        login("ada");
        assertThat(browser.get("/api/auth/me", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> logout = browser.post("/api/auth/logout", null, Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(browser.get("/api/auth/me", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The session must be destroyed server-side, not merely forgotten by the client.
     * Replaying the captured cookie after logout proves the session is gone from Redis
     * rather than still sitting there waiting to be reused.
     */
    @Test
    void logoutInvalidatesTheSessionServerSideSoTheCookieCannotBeReplayed() {
        register("ada", "ada@example.com");
        login("ada");

        String capturedSessionCookie = browser.cookie("CODEARENA_SESSION");
        assertThat(capturedSessionCookie).isNotNull();

        browser.post("/api/auth/logout", null, Void.class);

        HttpHeaders replay = new HttpHeaders();
        replay.add(HttpHeaders.COOKIE, "CODEARENA_SESSION=" + capturedSessionCookie);
        replay.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(replay), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Logging in must not keep the pre-login session id (session fixation defence). */
    @Test
    void issuesAFreshSessionIdOnLogin() {
        register("ada", "ada@example.com");

        browser.get("/api/auth/me", Map.class);            // anonymous; may create a session
        String beforeLogin = browser.cookie("CODEARENA_SESSION");

        login("ada");
        String afterLogin = browser.cookie("CODEARENA_SESSION");

        assertThat(afterLogin).isNotNull();
        if (beforeLogin != null) {
            assertThat(afterLogin).isNotEqualTo(beforeLogin);
        }
    }

    // ------------------------------------------------------------------------ CSRF

    @Test
    void issuesACsrfTokenCookieToTheBrowser() {
        browser.get("/api/system/info", Map.class);

        assertThat(browser.hasCookie("XSRF-TOKEN")).isTrue();
    }

    /**
     * A state-changing request without the CSRF header is rejected. This is what stops a
     * third-party page from POSTing on behalf of a logged-in user, since the browser
     * attaches the session cookie automatically but cannot read the token to echo it.
     */
    @Test
    void rejectsAStateChangingRequestThatOmitsTheCsrfToken() {
        register("ada", "ada@example.com");
        login("ada");

        browser.forgetCsrfToken();
        ResponseEntity<Map> response = browser.post("/api/auth/logout", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // And the session survived the rejected request.
        assertThat(browser.get("/api/auth/me", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------------------- authorization

    /**
     * The admin rule is enforced before any admin endpoint exists. An authenticated
     * ADMIN reaching 404 is the point: authorisation passed and routing simply found no
     * handler, whereas a USER never gets that far.
     */
    @Test
    void guardsTheAdminPathByRole() {
        ResponseEntity<Map> anonymous = browser.get("/api/admin/anything", Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("error", "AUTHENTICATION_REQUIRED");

        register("ada", "ada@example.com");
        login("ada");
        ResponseEntity<Map> asUser = browser.get("/api/admin/anything", Map.class);
        assertThat(asUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asUser.getBody()).containsEntry("error", "ACCESS_DENIED");

        createUser("root", "root@example.com", Role.ADMIN);
        BrowserClient adminBrowser = new BrowserClient(restTemplate);
        adminBrowser.get("/api/system/info", Map.class);
        adminBrowser.post("/api/auth/login", new LoginRequest("root", STRONG_PASSWORD), Map.class);

        ResponseEntity<Map> asAdmin = adminBrowser.get("/api/admin/anything", Map.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asAdmin.getBody()).containsEntry("error", "RESOURCE_NOT_FOUND");
    }

    @Test
    void keepsPublicEndpointsPublic() {
        assertThat(browser.get("/api/system/info", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/actuator/health", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/v3/api-docs", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Actuator beyond health is administrative and must not be world-readable. */
    @Test
    void protectsNonHealthActuatorEndpoints() {
        assertThat(browser.get("/actuator/info", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --------------------------------------------------------------------- helpers

    private void register(String username, String email) {
        ResponseEntity<Map> response = browser.post("/api/auth/register",
                new RegistrationRequest(username, email, STRONG_PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void login(String identifier) {
        ResponseEntity<Map> response = browser.post("/api/auth/login",
                new LoginRequest(identifier, STRONG_PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void createUser(String username, String email, Role role) {
        userRepository.saveAndFlush(
                User.create(username, email, passwordEncoder.encode(STRONG_PASSWORD), role));
    }
}
