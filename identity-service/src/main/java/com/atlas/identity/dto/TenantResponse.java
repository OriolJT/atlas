package com.atlas.identity.dto;

import com.atlas.identity.domain.Tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID tenantId,
        String name,
        String slug,
        String status,
        int maxWorkflowDefinitions,
        int maxExecutionsPerMinute,
        int maxConcurrentExecutions,
        int maxApiRequestsPerMinute,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getStatus().name(),
                tenant.getMaxWorkflowDefinitions(),
                tenant.getMaxExecutionsPerMinute(),
                tenant.getMaxConcurrentExecutions(),
                tenant.getMaxApiRequestsPerMinute(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }
}
