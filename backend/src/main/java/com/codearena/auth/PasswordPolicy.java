package com.codearena.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The rules a new password must satisfy.
 *
 * <p>Follows NIST SP 800-63B: length and screening against known-bad choices, rather
 * than composition rules. Forcing a symbol and a digit reliably produces
 * {@code Password1!} — it raises user frustration far more than it raises entropy — so
 * no such rule is imposed here.
 *
 * <p>The upper bound is not arbitrary. BCrypt silently ignores everything past 72 bytes,
 * so a 100-character passphrase would be truncated without warning and two different
 * passwords sharing a 72-byte prefix would both authenticate. Rejecting over-long input
 * outright is honest; quietly truncating it is not. The limit is measured in UTF-8
 * bytes, because a passphrase of emoji or non-Latin script reaches 72 bytes long before
 * it reaches 72 characters.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES = 72;

    /**
     * A deliberately short screen of passwords that appear at the very top of every
     * breach corpus. This is not a substitute for a full breached-password service
     * (Have I Been Pwned's k-anonymity API would be the real answer, and is noted as a
     * future improvement); it removes the most-guessed handful at zero cost and with no
     * network dependency in the registration path.
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "passw0rd", "p@ssw0rd",
            "123456789", "1234567890", "12345678910", "qwertyuiop", "qwerty123",
            "letmein123", "welcome123", "admin12345", "iloveyou123", "monkey12345",
            "football123", "baseball123", "sunshine123", "princess123", "dragon12345",
            "codearena", "codearena123", "changeme123", "secret1234", "trustno1234");

    /**
     * @return the reason the password is unacceptable, or empty if it passes
     */
    public Optional<String> validate(String rawPassword, String username, String email) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return Optional.of("Password is required");
        }
        if (rawPassword.length() < MIN_LENGTH) {
            return Optional.of("Password must be at least " + MIN_LENGTH + " characters long");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("Password must be at most " + MAX_BYTES + " bytes long");
        }
        if (rawPassword.isBlank()) {
            return Optional.of("Password must contain more than whitespace");
        }

        String normalised = rawPassword.toLowerCase(Locale.ROOT);
        if (COMMON_PASSWORDS.contains(normalised)) {
            return Optional.of("Password is too common; choose something less predictable");
        }
        if (containsIdentifier(normalised, username)) {
            return Optional.of("Password must not contain your username");
        }
        if (containsIdentifier(normalised, localPart(email))) {
            return Optional.of("Password must not contain your email address");
        }
        return Optional.empty();
    }

    /**
     * Short identifiers are ignored: a three-letter username would otherwise ban a large
     * share of ordinary passphrases for no security benefit.
     */
    private boolean containsIdentifier(String normalisedPassword, String identifier) {
        if (identifier == null || identifier.length() < 4) {
            return false;
        }
        return normalisedPassword.contains(identifier.toLowerCase(Locale.ROOT));
    }

    private String localPart(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
