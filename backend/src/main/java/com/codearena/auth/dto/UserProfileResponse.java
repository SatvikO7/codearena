package com.codearena.auth.dto;

import com.codearena.user.Role;
import com.codearena.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * The only shape in which a user is ever returned.
 *
 * <p>It is a closed record with no password field, so leaking a hash would require
 * someone to add one deliberately rather than merely forget to exclude it. The
 * identifier is the account's {@code publicId}; the internal numeric key is never
 * exposed.
 */
public record UserProfileResponse(
        UUID id,
        String username,
        String email,
        Role role,
        boolean enabled,
        Instant createdAt) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
