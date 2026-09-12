package com.codearena.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsALongPassphraseWithNoCompositionRules() {
        // No digit, no symbol, no capital: length and unpredictability are what matter.
        assertThat(policy.validate("correct horse battery staple", "ada", "ada@example.com"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "123456789"})
    void rejectsPasswordsBelowTheMinimumLength(String tooShort) {
        assertThat(policy.validate(tooShort, "ada", "ada@example.com")).isPresent();
    }

    @Test
    void rejectsNullPassword() {
        assertThat(policy.validate(null, "ada", "ada@example.com"))
                .contains("Password is required");
    }

    /**
     * BCrypt ignores input past 72 bytes. Accepting a longer password would mean two
     * different passwords sharing a 72-byte prefix both authenticate, so the policy
     * rejects rather than silently truncates.
     */
    @Test
    void rejectsPasswordsLongerThanBcryptCanHash() {
        String seventyThreeBytes = "a".repeat(73);
        assertThat(seventyThreeBytes.getBytes(StandardCharsets.UTF_8)).hasSize(73);

        assertThat(policy.validate(seventyThreeBytes, "ada", "ada@example.com"))
                .isPresent()
                .get().asString().contains("72 bytes");
    }

    /**
     * The limit is bytes, not characters: multi-byte input reaches BCrypt's ceiling long
     * before it looks long to a human.
     */
    @Test
    void measuresTheUpperBoundInBytesRatherThanCharacters() {
        String twentyFiveEmoji = "🔒".repeat(25);   // 4 bytes each = 100 bytes
        assertThat(twentyFiveEmoji.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

        assertThat(policy.validate(twentyFiveEmoji, "ada", "ada@example.com")).isPresent();

        String fifteenEmoji = "🔒".repeat(15);      // 60 bytes, comfortably within range
        assertThat(policy.validate(fifteenEmoji, "ada", "ada@example.com")).isEmpty();
    }

    @Test
    void rejectsPasswordsFromTheCommonList() {
        assertThat(policy.validate("password123", "ada", "ada@example.com"))
                .isPresent()
                .get().asString().contains("too common");
    }

    @Test
    void screensTheCommonListRegardlessOfCasing() {
        assertThat(policy.validate("PassWord123", "ada", "ada@example.com")).isPresent();
    }

    @Test
    void rejectsAPasswordContainingTheUsername() {
        assertThat(policy.validate("lovelace-in-my-password", "lovelace", "ada@example.com"))
                .isPresent()
                .get().asString().contains("username");
    }

    @Test
    void rejectsAPasswordContainingTheEmailLocalPart() {
        assertThat(policy.validate("my-adalovelace-secret", "ada", "adalovelace@example.com"))
                .isPresent()
                .get().asString().contains("email");
    }

    /**
     * A very short username must not ban every passphrase that happens to contain those
     * letters, or "ada" would reject "a parade of horses".
     */
    @Test
    void ignoresVeryShortIdentifiersWhenScanningForContainment() {
        assertThat(policy.validate("a parade of wild horses", "ada", "ada@example.com"))
                .isEmpty();
    }

    @Test
    void rejectsWhitespaceOnlyPasswords() {
        assertThat(policy.validate(" ".repeat(12), "ada", "ada@example.com")).isPresent();
    }
}
