package com.atlas.identity.security;

import java.util.List;
import java.util.UUID;

public record JwtAuthenticationDetails(UUID userId, UUID tenantId, List<String> roles) {
}
