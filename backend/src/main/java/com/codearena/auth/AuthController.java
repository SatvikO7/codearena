package com.codearena.auth;

import com.codearena.audit.ActorType;
import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.auth.dto.LoginRequest;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.auth.dto.UserProfileResponse;
import com.codearena.ratelimit.CallerIdentity;
import com.codearena.ratelimit.RateLimitDecision;
import com.codearena.ratelimit.RateLimitExceededException;
import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimitService;
import com.codearena.ratelimit.RateLimited;
import com.codearena.user.User;
import com.codearena.user.UserRegistrationService;
import com.codearena.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, logout and "who am I".
 *
 * <p>Authenticated state is a server-side session keyed by an HttpOnly cookie. The
 * browser never sees a credential it could read from JavaScript, and logging out
 * destroys the session on the server rather than merely discarding client state.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Account creation and session lifecycle")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

    private final UserRegistrationService registrationService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;

    public AuthController(UserRegistrationService registrationService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          UserRepository userRepository,
                          AuditService auditService,
                          RateLimitService rateLimitService) {
        this.registrationService = registrationService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account",
               description = """
                       Creates a USER account. Registration does not log you in; call
                       `POST /api/auth/login` afterwards.

                       The password must be at least 10 characters, at most 72 bytes, and
                       must not contain your username or email address.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Username or email already in use", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "429", description = "Too many registrations from this client", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @RateLimited(RateLimitPolicy.REGISTRATION)
    public ResponseEntity<UserProfileResponse> register(@Valid @RequestBody RegistrationRequest request) {
        User created = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserProfileResponse.from(created));
    }

    @PostMapping("/login")
    @Operation(summary = "Start a session",
               description = """
                       Authenticates with a username **or** email address and establishes a
                       session. The session id is returned as an HttpOnly cookie; there is no
                       token in the response body for JavaScript to store or leak.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Account disabled", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "429", description = "Too many login attempts", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @RateLimited(RateLimitPolicy.LOGIN_ORIGIN)
    public ResponseEntity<UserProfileResponse> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {

        CallerIdentity account = CallerIdentity.ofAccount(request.identifier());
        throttleGuessingAt(account);

        Authentication authentication = authenticate(request);
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        // The password was right, so nothing that came before it was an attack on this
        // account. Clearing the record keeps a legitimate user from accumulating against
        // themselves, and means the throttle only ever reflects unsuccessful guessing.
        rateLimitService.reset(RateLimitPolicy.LOGIN_ACCOUNT, account);

        // Session fixation defence: discard any session the caller arrived holding, so the
        // authenticated session is always one this server has just issued. An attacker who
        // planted a session id before login therefore holds a dead one.
        HttpSession existingSession = httpRequest.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }
        httpRequest.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // The filter chain runs with requireExplicitSave, so the context reaches the
        // session, and Redis, only because it is saved here.
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // Independent of any transaction: nothing was changed in the domain, and there is
        // no mutation for this record to be consistent with.
        auditService.recordIndependently(AuditAction.AUTH_LOGIN, AuditOutcome.SUCCESS,
                AuditEntityType.USER, principal.getPublicId().toString(),
                AuditMetadata.of().put("role", principal.getRole()).build());

        log.info("Login succeeded for publicId={}", principal.getPublicId());

        return ResponseEntity.ok(loadProfile(principal));
    }

    private Authentication authenticate(LoginRequest request) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.identifier(), request.password()));
        } catch (DisabledException e) {
            auditLoginFailure(request.identifier(), "ACCOUNT_DISABLED");
            // Distinguished from bad credentials deliberately: telling a suspended user
            // that their account is disabled is far better than letting them believe they
            // have forgotten their password. Registration already reveals which usernames
            // exist, so this leaks nothing that was previously hidden.
            log.info("Login rejected for a disabled account");
            throw new AuthenticationFailedException(
                    ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "This account has been disabled");
        } catch (org.springframework.security.core.AuthenticationException e) {
            auditLoginFailure(request.identifier(), "INVALID_CREDENTIALS");
            // One message for "no such user" and "wrong password" so that login cannot be
            // used to enumerate accounts.
            log.info("Login failed: bad credentials");
            throw new AuthenticationFailedException(
                    INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    /**
     * The second dimension of login protection: a limit on guessing at <em>one account</em>,
     * however many places the guesses come from.
     *
     * <p>{@link RateLimitPolicy#LOGIN_ORIGIN} already caps how fast one caller can try, but
     * it is keyed on a network identity that this deployment cannot always tell apart, and
     * credential stuffing is distributed by nature. This one holds regardless: the bucket
     * belongs to the account being attacked, so spreading the attempts across a thousand
     * sources does not multiply the allowance.
     *
     * <h2>A throttle, deliberately not a lockout</h2>
     * The obvious version of this control — "N failures and the account is locked" — hands
     * an attacker a denial of service against any user whose name they can guess, which is
     * a worse bug than the one being fixed. A token bucket cannot lock anything: it refills
     * continuously, so the account owner is never more than one refill interval away from
     * an attempt, and the correct password empties the bucket outright. The worst an
     * attacker achieves is to make the real user wait, briefly, and try again.
     *
     * <p>The residual weakness is stated rather than papered over: while a sustained
     * distributed attack is running against one account, its owner is competing for each
     * newly regenerated token and may need more than one attempt to get in. They are
     * delayed, not locked out, and the delay ends when the attack does. See
     * {@code docs/rate-limiting.md}.
     *
     * <p>The check happens before authentication, which is the point — the expensive part of
     * a login is the BCrypt verification at cost 12, and a control that ran afterwards would
     * have already paid for the attack it was meant to prevent.
     */
    private void throttleGuessingAt(CallerIdentity account) {
        RateLimitDecision decision = rateLimitService.check(RateLimitPolicy.LOGIN_ACCOUNT, account);
        if (!decision.allowed()) {
            // Not audited: the identity is derived from caller-supplied text, so an event
            // per rejection would let anybody write unbounded rows into an append-only
            // table by inventing usernames. See RateLimitViolationAuditor.
            log.info("Login throttled for an account under repeated failed attempts");
            throw new RateLimitExceededException(RateLimitPolicy.LOGIN_ACCOUNT, decision);
        }
    }

    /**
     * Records a rejected login.
     *
     * <p>The most security-relevant event the system produces: a run of these against one
     * account, or from one source, is what credential stuffing looks like.
     *
     * <p><b>What goes in, and what deliberately does not.</b> The identifier that was tried
     * and a coarse reason. Never the password, obviously — but also nothing that would say
     * whether the account exists. Login itself answers identically for "no such user" and
     * "wrong password" precisely so it cannot be used to enumerate accounts, and an audit
     * record that distinguished them would hand back the same oracle to anyone who could
     * read the log. ACCOUNT_DISABLED is the one exception, and only because login already
     * discloses that state to the caller.
     *
     * <p>The identifier is attacker-controlled text. It is bounded and redaction-checked by
     * AuditMetadata like any other value, and it is recorded as <em>what was attempted</em>
     * rather than as an actor. The actor is whoever made the request, which for the usual
     * case -- a signed-out caller guessing a password -- is ANONYMOUS. It is deliberately
     * not forced to ANONYMOUS: an attempt made from an existing session really was made by
     * that session, and recording the identity behind a failed attempt on somebody else's
     * account is worth more than a uniform field.
     */
    private void auditLoginFailure(String identifier, String reason) {
        auditService.recordIndependently(AuditAction.AUTH_LOGIN_FAILURE, AuditOutcome.FAILURE,
                null, null,
                AuditMetadata.of()
                        .put("attemptedIdentifier", identifier)
                        .put("reason", reason)
                        .build());
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session",
               description = """
                       Invalidates the server-side session and clears the session cookie.
                       The session is destroyed in Redis, so the cookie cannot be replayed
                       even if it was captured.
                       """)
    @ApiResponse(responseCode = "204", description = "Session ended")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser actor = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal
                ? principal
                : null;

        // Invalidates the HttpSession (removing it from Redis), clears the context and
        // clears the context holder. Deleting the cookie alone would leave a usable
        // session on the server.
        SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
        logoutHandler.setInvalidateHttpSession(true);
        logoutHandler.setClearAuthentication(true);
        logoutHandler.logout(request, response, authentication);

        // After the handler, so the event is only recorded once the session is genuinely
        // gone. The actor was captured above, while it still existed -- by this point there
        // is nobody left in the security context to attribute it to.
        //
        // Independent of any transaction: nothing in the domain changed.
        if (actor != null) {
            auditService.recordIndependentlyFor(actor.getPublicId(), actor.getUsername(),
                    actor.getRole() == com.codearena.user.Role.ADMIN ? ActorType.ADMIN : ActorType.USER,
                    AuditAction.AUTH_LOGOUT, AuditOutcome.SUCCESS,
                    AuditEntityType.USER, actor.getPublicId().toString(), java.util.Map.of());
        }

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "The current user",
               description = "Returns the authenticated account, or 401 when there is no valid session.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The authenticated account"),
            @ApiResponse(responseCode = "401", description = "No valid session", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public UserProfileResponse currentUser(@AuthenticationPrincipal AuthenticatedUser principal) {
        return loadProfile(principal);
    }

    /**
     * Reads the account fresh rather than trusting the session copy, so a role change or
     * a disabled flag set by an administrator is reflected on the next call instead of
     * lingering until the session expires.
     */
    private UserProfileResponse loadProfile(AuthenticatedUser principal) {
        return userRepository.findByPublicId(principal.getPublicId())
                .map(UserProfileResponse::from)
                .orElseThrow(() -> new AuthenticationFailedException(
                        INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Session refers to an account that no longer exists"));
    }
}
