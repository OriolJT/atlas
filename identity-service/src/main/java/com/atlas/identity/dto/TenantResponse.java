package com.atlas.identity.dto;

import com.atlas.identity.domain.Tenant;

import java.time.LocalDateTime;
import java.util.UUID;

public record TenantResponse(
        UUID tenantId,
        String name,
        String slug,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getStatus().name(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }
}
