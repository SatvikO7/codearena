package com.codearena.user;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * What a user is allowed to do.
 *
 * <p>A user holds exactly one role. That is a deliberate simplification, not an
 * oversight: an online judge distinguishes people who solve problems from people who
 * author them, and nothing in the roadmap needs a single account to hold two roles at
 * once. Adding a role later means adding a constant here and a value to the
 * {@code ck_users_role} check constraint; no authentication code changes. Should a
 * genuine many-to-many requirement appear, it becomes a join table at that point,
 * because by then it would be a real requirement rather than a guess.
 *
 * <p>Roles are persisted by {@link #name()}, never by ordinal, so reordering this enum
 * cannot silently change anyone's privileges.
 */
public enum Role {

    /** Can solve problems, submit code and manage their own profile. */
    USER,

    /** Everything a USER can do, plus authoring and administration. */
    ADMIN;

    /**
     * Spring Security's {@code hasRole("ADMIN")} checks for an authority literally named
     * {@code ROLE_ADMIN}. Keeping that prefix in one place stops the string from being
     * re-derived, and mistyped, across the codebase.
     */
    public static final String AUTHORITY_PREFIX = "ROLE_";

    public GrantedAuthority toAuthority() {
        return new SimpleGrantedAuthority(AUTHORITY_PREFIX + name());
    }
}
