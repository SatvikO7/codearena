package com.codearena.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A registered account.
 *
 * <p>The password hash is held in this entity because persistence requires it, but it
 * leaves the entity only through {@link #getPasswordHash()}, which exists solely for the
 * authentication provider. Nothing serialises a {@code User} directly: controllers
 * return {@link com.codearena.auth.dto.UserProfileResponse}, so there is no path by
 * which the hash can reach a response body.
 */
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The only identifier the API exposes. See V2__create_users.sql for why. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    /**
     * Case-insensitive uniqueness is enforced by a unique index over {@code lower(username)};
     * see V2__create_users.sql. The column itself stores the casing the user chose.
     */
    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // Required by JPA.
    }

    private User(UUID publicId, String username, String email, String passwordHash, Role role, boolean enabled) {
        this.publicId = publicId;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    /**
     * Creates an enabled account. The caller supplies an already-encoded hash: this
     * class has no opinion about hashing, and cannot be handed a plaintext password by
     * accident because nothing here accepts one.
     */
    public static User create(String username, String email, String passwordHash, Role role) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(role, "role");
        return new User(UUID.randomUUID(), username, email, passwordHash, role, true);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    /** Used only by the authentication provider when verifying a login attempt. */
    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Identity is the persistent key. A transient instance is equal only to itself,
     * which keeps two unsaved users from colliding in a collection.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user) || id == null) {
            return false;
        }
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }

    /** Deliberately excludes the password hash so it cannot reach a log line. */
    @Override
    public String toString() {
        return "User{id=%d, publicId=%s, username='%s', role=%s, enabled=%s}"
                .formatted(id, publicId, username, role, enabled);
    }
}
