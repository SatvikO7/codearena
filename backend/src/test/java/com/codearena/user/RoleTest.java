package com.codearena.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    /**
     * {@code hasRole("ADMIN")} looks for an authority literally named {@code ROLE_ADMIN}.
     * If this mapping ever drops the prefix, every role check silently stops matching and
     * authorisation fails open or closed depending on the rule — so it is pinned here.
     */
    @Test
    void mapsEachRoleToThePrefixedAuthoritySpringSecurityExpects() {
        assertThat(Role.USER.toAuthority().getAuthority()).isEqualTo("ROLE_USER");
        assertThat(Role.ADMIN.toAuthority().getAuthority()).isEqualTo("ROLE_ADMIN");
    }

    /**
     * Roles are persisted by name. Reordering the enum must not change what an existing
     * row means, which is why the column is text rather than an ordinal.
     */
    @Test
    void persistsByNameSoReorderingTheEnumCannotChangePrivileges() {
        assertThat(Role.valueOf("USER")).isEqualTo(Role.USER);
        assertThat(Role.valueOf("ADMIN")).isEqualTo(Role.ADMIN);
        assertThat(Role.values()).extracting(Enum::name).containsExactlyInAnyOrder("USER", "ADMIN");
    }
}
