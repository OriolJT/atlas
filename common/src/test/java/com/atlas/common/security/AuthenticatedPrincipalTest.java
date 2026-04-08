package com.atlas.common.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedPrincipalTest {

    @Test
    void createWithValidArguments() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        List<String> roles = List.of("admin", "user");

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, tenantId, roles);

        assertEquals(userId, principal.userId());
        assertEquals(tenantId, principal.tenantId());
        assertEquals(roles, principal.roles());
    }

    @Test
    void rejectNullUserId() {
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedPrincipal(null, UUID.randomUUID(), List.of()));
    }

    @Test
    void rejectNullTenantId() {
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedPrincipal(UUID.randomUUID(), null, List.of()));
    }

    @Test
    void rejectNullRoles() {
        assertThrows(NullPointerException.class,
                () -> new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), null));
    }

    @Test
    void rolesAreImmutable() {
        List<String> mutableRoles = new ArrayList<>();
        mutableRoles.add("admin");

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), mutableRoles);

        assertThrows(UnsupportedOperationException.class,
                () -> principal.roles().add("hacker"));
    }

    @Test
    void mutatingOriginalListDoesNotAffectPrincipal() {
        List<String> mutableRoles = new ArrayList<>();
        mutableRoles.add("admin");

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), mutableRoles);

        mutableRoles.add("hacker");

        assertEquals(1, principal.roles().size());
        assertTrue(principal.roles().contains("admin"));
    }

    @Test
    void hasRoleReturnsTrueWhenRolePresent() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), List.of("admin", "user"));

        assertTrue(principal.hasRole("admin"));
        assertTrue(principal.hasRole("user"));
    }

    @Test
    void hasRoleReturnsFalseWhenRoleAbsent() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), List.of("user"));

        assertFalse(principal.hasRole("admin"));
    }

    @Test
    void hasRoleReturnsFalseForEmptyRoles() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertFalse(principal.hasRole("admin"));
    }
}
