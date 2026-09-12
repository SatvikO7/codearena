package com.codearena.auth;

import com.codearena.user.Role;
import com.codearena.user.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal placed in the security context and, through it, into the
 * session stored in Redis.
 *
 * <p>It carries the account's stable {@code publicId} rather than a {@link User} entity.
 * Two reasons: a detached JPA entity in a session is a source of stale data and
 * lazy-loading surprises, and everything stored in a session is serialised to Redis, so
 * it should be a small, stable value.
 *
 * <p>The password hash is held only because {@link UserDetails} requires it for the
 * authentication provider's comparison, and is erased immediately afterwards by Spring
 * Security's {@code eraseCredentials}.
 */
public class AuthenticatedUser implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID publicId;
    private final String username;
    private final Role role;
    private final boolean enabled;
    private transient String passwordHash;

    public AuthenticatedUser(UUID publicId, String username, String passwordHash, Role role, boolean enabled) {
        this.publicId = publicId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(
                user.getPublicId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), user.isEnabled());
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(role.toAuthority());
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser{publicId=%s, username='%s', role=%s}".formatted(publicId, username, role);
    }
}
