package com.atlas.worker.config;

import com.atlas.common.ratelimit.TenantQuotaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves per-tenant rate limits by calling identity-service's internal quota API.
 * Caches results for 60 seconds. Falls back to {@code defaultRpm} on any REST failure.
 */
@Primary
@Component
public class RestTenantQuotaResolver implements TenantQuotaResolver {

    private static final Logger log = LoggerFactory.getLogger(RestTenantQuotaResolver.class);
    private static final long CACHE_TTL_MS = 60_000L;

    private final RestClient restClient;
    private final ConcurrentHashMap<UUID, CachedQuota> cache = new ConcurrentHashMap<>();

    public RestTenantQuotaResolver(
            @Value("${atlas.identity-service.url:http://localhost:8081}") String identityServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(identityServiceUrl)
                .build();
    }

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        CachedQuota cached = cache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return cached.requestsPerMinute();
        }

        try {
            TenantQuotaResponse response = restClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/quotas", tenantId)
                    .retrieve()
                    .body(TenantQuotaResponse.class);

            int rpm = (response != null) ? response.maxApiRequestsPerMinute() : defaultRpm;
            cache.put(tenantId, new CachedQuota(rpm, System.currentTimeMillis() + CACHE_TTL_MS));
            return rpm;
        } catch (Exception e) {
            log.warn("Failed to resolve quota for tenant {} from identity-service, using default {}: {}",
                    tenantId, defaultRpm, e.getMessage());
            return defaultRpm;
        }
    }

    private record TenantQuotaResponse(
            UUID tenantId,
            int maxWorkflowDefinitions,
            int maxExecutionsPerMinute,
            int maxConcurrentExecutions,
            int maxApiRequestsPerMinute) {
    }

    private record CachedQuota(int requestsPerMinute, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
