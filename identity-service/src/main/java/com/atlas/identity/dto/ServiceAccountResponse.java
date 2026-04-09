package com.atlas.identity.dto;

import com.atlas.identity.domain.ServiceAccount;

import java.time.Instant;
import java.util.UUID;

public record ServiceAccountResponse(
        UUID serviceAccountId,
        UUID tenantId,
        String name,
        String status,
        Instant createdAt) {

    public static ServiceAccountResponse from(ServiceAccount sa) {
        return new ServiceAccountResponse(
                sa.getServiceAccountId(),
                sa.getTenantId(),
                sa.getName(),
                sa.getStatus().name(),
                sa.getCreatedAt());
    }
}
