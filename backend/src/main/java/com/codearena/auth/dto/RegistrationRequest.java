package com.codearena.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration input.
 *
 * <p>Annotations here cover shape — length, character set, email form. Password strength
 * is checked by {@link com.codearena.auth.PasswordPolicy} instead of a regex, because
 * the rules are conditional (a password may not contain the username) and need to
 * explain precisely why they rejected the input.
 */
public record RegistrationRequest(

        @Schema(example = "ada_lovelace",
                description = "3-32 characters: letters, digits, underscore or hyphen.")
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 32, message = "Username must be between 3 and 32 characters")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                 message = "Username may contain only letters, digits, underscores and hyphens")
        String username,

        @Schema(example = "ada@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @Schema(example = "correct horse battery staple",
                description = "At least 10 characters. Must not contain your username or email.")
        @NotBlank(message = "Password is required")
        String password) {
}
