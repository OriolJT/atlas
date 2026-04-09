package com.atlas.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTenantQuotaResolverTest {

    private final DefaultTenantQuotaResolver resolver = new DefaultTenantQuotaResolver();

    @Test
    void returnsDefaultRpm() {
        UUID tenantId = UUID.randomUUID();
        assertThat(resolver.resolveRequestsPerMinute(tenantId, 120)).isEqualTo(120);
    }

    @Test
    void returnsSameValueForDifferentTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        assertThat(resolver.resolveRequestsPerMinute(tenantA, 60))
                .isEqualTo(resolver.resolveRequestsPerMinute(tenantB, 60));
    }
}
