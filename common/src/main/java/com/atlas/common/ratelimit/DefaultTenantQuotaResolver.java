package com.atlas.common.ratelimit;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default resolver -- always returns the global default. Replaced in Task 8. */
@Component
public class DefaultTenantQuotaResolver implements TenantQuotaResolver {

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        return defaultRpm;
    }
}
