package com.codearena.user;

import com.codearena.audit.ActorType;
import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.auth.PasswordPolicy;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.common.ConflictException;
import com.codearena.system.BusinessMetrics;
import com.codearena.common.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates accounts.
 *
 * <p>Uniqueness is enforced twice, on purpose. The {@code existsBy} pre-checks exist to
 * produce a precise, friendly error naming the offending field. The database's unique
 * constraints are what actually guarantee correctness: between the pre-check and the
 * insert, a concurrent request can claim the same username, and no amount of
 * application-level checking closes that window. The pre-check is a courtesy; the
 * constraint is the rule.
 */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    public static final String USERNAME_TAKEN = "USERNAME_ALREADY_TAKEN";
    public static final String EMAIL_TAKEN = "EMAIL_ALREADY_REGISTERED";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final BusinessMetrics metrics;

    public UserRegistrationService(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   PasswordPolicy passwordPolicy,
                                   AuditService auditService,
                                   BusinessMetrics metrics) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    @Transactional
    public User register(RegistrationRequest request) {
        String username = request.username().trim();
        // Emails are lower-cased on the way in for tidy display. Uniqueness does not
        // depend on that: the unique index is over lower(email), so the database rejects
        // a case-variant duplicate whatever casing reaches it.
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        passwordPolicy.validate(request.password(), username, email)
                .ifPresent(reason -> {
                    throw new ValidationException("password", reason);
                });

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException(USERNAME_TAKEN, "That username is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(EMAIL_TAKEN, "That email address is already registered");
        }

        User user = User.create(username, email, passwordEncoder.encode(request.password()), Role.USER);

        try {
            User saved = userRepository.saveAndFlush(user);

            // Inside the same transaction as the insert: an account cannot come into
            // existence without the record of its creation, and a registration that rolls
            // back cannot leave behind a record claiming it happened.
            //
            // The actor is the new account itself, passed explicitly because there is no
            // session yet -- registration deliberately does not log anybody in. The id is
            // one the server has just generated, never anything from the request.
            auditService.recordFor(saved.getPublicId(), saved.getUsername(), ActorType.USER,
                    AuditAction.AUTH_REGISTER, AuditOutcome.SUCCESS,
                    AuditEntityType.USER, saved.getPublicId().toString(),
                    AuditMetadata.of().put("role", saved.getRole()).build());

            metrics.authAttempt("register", "success");
            log.info("Registered user publicId={} role={}", saved.getPublicId(), saved.getRole());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration. Translate the constraint
            // violation into the same 409 the pre-check would have produced.
            throw translateConstraintViolation(e);
        }
    }

    private RuntimeException translateConstraintViolation(DataIntegrityViolationException e) {
        String detail = describe(e).toLowerCase(Locale.ROOT);
        if (detail.contains("uq_users_username_lower")) {
            return new ConflictException(USERNAME_TAKEN, "That username is already taken");
        }
        if (detail.contains("uq_users_email_lower")) {
            return new ConflictException(EMAIL_TAKEN, "That email address is already registered");
        }
        // Some other constraint failed. Let it surface as a 500 so it is investigated
        // rather than mislabelled as a duplicate account.
        return e;
    }

    private String describe(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = e; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append(' ');
        }
        return text.toString();
    }
}
