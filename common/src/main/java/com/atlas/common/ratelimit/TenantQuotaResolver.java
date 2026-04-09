package com.atlas.common.ratelimit;

import java.util.UUID;

/**
 * Resolves the effective requests-per-minute limit for a tenant.
 * Default implementation returns the global default.
 * Task 8 replaces this with a cache-backed implementation.
 */
public interface TenantQuotaResolver {

    /**
     * Returns requests-per-minute for the tenant, falling back to {@code defaultRpm}
     * when no tenant-specific quota is found.
     */
    int resolveRequestsPerMinute(UUID tenantId, int defaultRpm);
}
