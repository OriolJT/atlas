package com.atlas.identity.config;

import com.atlas.common.ratelimit.TenantQuotaResolver;
import com.atlas.identity.repository.TenantRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves per-tenant rate limits directly from the tenants table.
 * Caches results for 60 seconds to avoid hammering the DB on every request.
 */
@Primary
@Component
public class IdentityTenantQuotaResolver implements TenantQuotaResolver {

    private static final long CACHE_TTL_MS = 60_000L;

    private final TenantRepository tenantRepository;
    private final ConcurrentHashMap<UUID, CachedQuota> cache = new ConcurrentHashMap<>();

    public IdentityTenantQuotaResolver(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        CachedQuota cached = cache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return cached.requestsPerMinute();
        }

        int rpm = tenantRepository.findById(tenantId)
                .map(t -> t.getMaxApiRequestsPerMinute())
                .orElse(defaultRpm);

        cache.put(tenantId, new CachedQuota(rpm, System.currentTimeMillis() + CACHE_TTL_MS));
        return rpm;
    }

    private record CachedQuota(int requestsPerMinute, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
