package com.codearena.user;

import com.codearena.auth.PasswordPolicy;
import com.codearena.auth.dto.RegistrationRequest;
import com.codearena.common.ConflictException;
import com.codearena.common.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRegistrationServiceTest {

    private UserRepository userRepository;
    private UserRegistrationService service;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserRegistrationService(userRepository, passwordEncoder, new PasswordPolicy());
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private RegistrationRequest request(String username, String email, String password) {
        return new RegistrationRequest(username, email, password);
    }

    @Test
    void storesAHashRatherThanThePlaintextPassword() {
        User created = service.register(request("ada", "ada@example.com", "a good long passphrase"));

        assertThat(created.getPasswordHash())
                .isNotEqualTo("a good long passphrase")
                .startsWith("$2");
        assertThat(passwordEncoder.matches("a good long passphrase", created.getPasswordHash())).isTrue();
    }

    @Test
    void assignsTheUserRoleAndEnablesTheAccount() {
        User created = service.register(request("ada", "ada@example.com", "a good long passphrase"));

        assertThat(created.getRole()).isEqualTo(Role.USER);
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getPublicId()).isNotNull();
    }

    /** Registering must never be a route to creating an administrator. */
    @Test
    void neverGrantsAdminThroughRegistration() {
        User created = service.register(request("admin", "admin@example.com", "a good long passphrase"));

        assertThat(created.getRole()).isNotEqualTo(Role.ADMIN);
    }

    @Test
    void lowercasesTheEmailAndTrimsTheUsername() {
        User created = service.register(request("  ada  ", "  Ada@Example.COM ", "a good long passphrase"));

        assertThat(created.getUsername()).isEqualTo("ada");
        assertThat(created.getEmail()).isEqualTo("ada@example.com");
    }

    @Test
    void rejectsAWeakPasswordBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "short")))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void reportsADuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("ada")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "a good long passphrase")))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo(UserRegistrationService.USERNAME_TAKEN);
    }

    @Test
    void reportsADuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "a good long passphrase")))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo(UserRegistrationService.EMAIL_TAKEN);
    }

    /**
     * The race the pre-checks cannot win: another request claims the username between
     * {@code existsByUsernameIgnoreCase} returning false and this insert landing. The database
     * constraint is what actually prevents the duplicate, and the violation must surface
     * as the same 409 a pre-check would have produced rather than as a 500.
     */
    @Test
    void translatesAConcurrentUsernameInsertIntoTheSameConflict() {
        whenSaveFails(name -> new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_users_username_lower\"")));

        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "a good long passphrase")))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo(UserRegistrationService.USERNAME_TAKEN);
    }

    @Test
    void translatesAConcurrentEmailInsertIntoTheSameConflict() {
        whenSaveFails(name -> new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_users_email_lower\"")));

        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "a good long passphrase")))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo(UserRegistrationService.EMAIL_TAKEN);
    }

    /**
     * An unrelated constraint failure must not be dressed up as a duplicate account; it
     * is a bug and should surface as one.
     */
    @Test
    void doesNotDisguiseAnUnrelatedConstraintFailureAsAConflict() {
        whenSaveFails(name -> new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: new row violates check constraint \"ck_users_role\"")));

        assertThatThrownBy(() -> service.register(request("ada", "ada@example.com", "a good long passphrase")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }

    private void whenSaveFails(Function<String, RuntimeException> failure) {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(failure.apply("save"));
    }
}
