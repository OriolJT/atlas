package com.atlas.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    private TenantContext context;
    private AuthenticatedPrincipal principal;

    @BeforeEach
    void setUp() {
        context = new TenantContext();
        principal = new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), List.of("admin"));
    }

    @Test
    void isNotAuthenticatedInitially() {
        assertFalse(context.isAuthenticated());
    }

    @Test
    void isAuthenticatedAfterSetPrincipal() {
        context.setPrincipal(principal);
        assertTrue(context.isAuthenticated());
    }

    @Test
    void getPrincipalReturnsSetPrincipal() {
        context.setPrincipal(principal);
        assertEquals(principal, context.getPrincipal());
    }

    @Test
    void getTenantIdReturnsTenantId() {
        context.setPrincipal(principal);
        assertEquals(principal.tenantId(), context.getTenantId());
    }

    @Test
    void getUserIdReturnsUserId() {
        context.setPrincipal(principal);
        assertEquals(principal.userId(), context.getUserId());
    }

    @Test
    void getPrincipalThrowsWhenNotAuthenticated() {
        assertThrows(IllegalStateException.class, context::getPrincipal);
    }

    @Test
    void getTenantIdThrowsWhenNotAuthenticated() {
        assertThrows(IllegalStateException.class, context::getTenantId);
    }

    @Test
    void getUserIdThrowsWhenNotAuthenticated() {
        assertThrows(IllegalStateException.class, context::getUserId);
    }

    @Test
    void clearRemovesPrincipal() {
        context.setPrincipal(principal);
        context.clear();
        assertFalse(context.isAuthenticated());
    }

    @Test
    void getPrincipalThrowsAfterClear() {
        context.setPrincipal(principal);
        context.clear();
        assertThrows(IllegalStateException.class, context::getPrincipal);
    }
}
