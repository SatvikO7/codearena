package com.codearena.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Login accepts either identifier, so one query covers both columns.
     *
     * <p>Both sides are wrapped in {@code lower(...)} to match the unique indexes on
     * {@code lower(username)} and {@code lower(email)}: comparing the bare column would
     * be case-sensitive and would not use those indexes.
     */
    @Query("SELECT u FROM User u "
         + "WHERE lower(u.username) = lower(:identifier) OR lower(u.email) = lower(:identifier)")
    Optional<User> findByUsernameOrEmail(@Param("identifier") String identifier);

    Optional<User> findByPublicId(UUID publicId);

    /** Derived queries ending in IgnoreCase generate lower(...) = lower(?), hitting the index. */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);
}
