package com.atlas.identity.dto;

import com.atlas.identity.domain.Tenant;

import java.util.UUID;

public record TenantQuotaResponse(
        UUID tenantId,
        int maxWorkflowDefinitions,
        int maxExecutionsPerMinute,
        int maxConcurrentExecutions,
        int maxApiRequestsPerMinute) {

    public static TenantQuotaResponse from(Tenant tenant) {
        return new TenantQuotaResponse(
                tenant.getTenantId(),
                tenant.getMaxWorkflowDefinitions(),
                tenant.getMaxExecutionsPerMinute(),
                tenant.getMaxConcurrentExecutions(),
                tenant.getMaxApiRequestsPerMinute());
    }
}
