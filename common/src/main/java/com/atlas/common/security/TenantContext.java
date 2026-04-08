package com.atlas.common.security;

import java.util.UUID;

/**
 * Request-scoped holder for the authenticated principal.
 * <p>
 * This is a plain class in the common module. When used in a Spring context it
 * should be declared as a {@code @RequestScope} bean so each HTTP request gets
 * its own instance (virtual-thread safe, no ThreadLocal required).
 */
public class TenantContext {

    private AuthenticatedPrincipal principal;

    public void setPrincipal(AuthenticatedPrincipal principal) {
        this.principal = principal;
    }

    public AuthenticatedPrincipal getPrincipal() {
        if (principal == null) {
            throw new IllegalStateException("No authenticated principal available");
        }
        return principal;
    }

    public UUID getTenantId() {
        return getPrincipal().tenantId();
    }

    public UUID getUserId() {
        return getPrincipal().userId();
    }

    public boolean isAuthenticated() {
        return principal != null;
    }

    public void clear() {
        principal = null;
    }
}
