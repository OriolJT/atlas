package com.atlas.workflow.dto;

import java.util.UUID;

/**
 * Mirrors the identity-service's TenantQuotaResponse for deserialization.
 */
public record TenantQuotaResponse(
        UUID tenantId,
        int maxWorkflowDefinitions,
        int maxExecutionsPerMinute,
        int maxConcurrentExecutions,
        int maxApiRequestsPerMinute) {
}
