package com.atlas.common.security;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, UUID tenantId, List<String> roles) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        roles = List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
