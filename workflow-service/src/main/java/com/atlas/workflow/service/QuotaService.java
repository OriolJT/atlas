package com.atlas.workflow.service;

import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.dto.TenantQuotaResponse;
import com.atlas.workflow.exception.QuotaExceededException;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces per-tenant quotas by fetching limits from identity-service and
 * comparing them against current usage in the database.
 *
 * Quota data is cached in-memory for 60 seconds per tenant to avoid hammering
 * the identity-service on every request.
 *
 * When the identity-service is unreachable (e.g. local dev / unit tests),
 * the service falls back to configurable defaults set via
 * {@code atlas.quota.default-max-definitions},
 * {@code atlas.quota.default-max-executions-per-minute}, and
 * {@code atlas.quota.default-max-concurrent-executions}.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** Statuses that count as "active / concurrent" executions. */
    private static final List<ExecutionStatus> ACTIVE_STATUSES =
            List.of(ExecutionStatus.RUNNING, ExecutionStatus.PENDING, ExecutionStatus.WAITING);

    private static final int MAX_CACHE_SIZE = 10_000;

    /** Cache entry: quota response + the instant it was fetched. */
    private record CacheEntry(TenantQuotaResponse quota, Instant fetchedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plusSeconds(60));
        }
    }

    private final Map<UUID, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CacheEntry> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    private final RestClient restClient;
    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowExecutionRepository executionRepository;

    private final int defaultMaxDefinitions;
    private final int defaultMaxExecutionsPerMinute;
    private final int defaultMaxConcurrentExecutions;
    private final String internalApiKey;

    public QuotaService(
            RestClient.Builder restClientBuilder,
            @Value("${atlas.identity-service.url:http://identity-service:8081}") String identityServiceUrl,
            WorkflowDefinitionRepository definitionRepository,
            WorkflowExecutionRepository executionRepository,
            @Value("${atlas.quota.default-max-definitions:100}") int defaultMaxDefinitions,
            @Value("${atlas.quota.default-max-executions-per-minute:60}") int defaultMaxExecutionsPerMinute,
            @Value("${atlas.quota.default-max-concurrent-executions:10}") int defaultMaxConcurrentExecutions,
            @Value("${atlas.internal.api-key}") String internalApiKey) {
        this.restClient = restClientBuilder
                .baseUrl(identityServiceUrl)
                .build();
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.defaultMaxDefinitions = defaultMaxDefinitions;
        this.defaultMaxExecutionsPerMinute = defaultMaxExecutionsPerMinute;
        this.defaultMaxConcurrentExecutions = defaultMaxConcurrentExecutions;
        this.internalApiKey = internalApiKey;
    }

    /**
     * Checks whether the tenant is below the max_workflow_definitions quota.
     *
     * @throws QuotaExceededException if the limit has been reached
     */
    public void checkDefinitionQuota(UUID tenantId) {
        TenantQuotaResponse quota = getQuota(tenantId);
        long current = definitionRepository.countByTenantId(tenantId);
        int limit = quota.maxWorkflowDefinitions();
        if (current >= limit) {
            throw new QuotaExceededException("max_workflow_definitions", current, limit);
        }
    }

    /**
     * Checks whether the tenant is below the max_executions_per_minute quota
     * by counting executions created in the last 60 seconds.
     *
     * @throws QuotaExceededException if the limit has been reached
     */
    public void checkExecutionQuota(UUID tenantId) {
        TenantQuotaResponse quota = getQuota(tenantId);
        Instant oneMinuteAgo = Instant.now().minusSeconds(60);
        long current = executionRepository.countByTenantIdAndCreatedAtAfter(tenantId, oneMinuteAgo);
        int limit = quota.maxExecutionsPerMinute();
        if (current >= limit) {
            throw new QuotaExceededException("max_executions_per_minute", current, limit);
        }
    }

    /**
     * Checks whether the tenant is below the max_concurrent_executions quota
     * by counting executions currently in RUNNING, PENDING, or WAITING state.
     *
     * @throws QuotaExceededException if the limit has been reached
     */
    public void checkConcurrentExecutionQuota(UUID tenantId) {
        TenantQuotaResponse quota = getQuota(tenantId);
        long current = executionRepository.countByTenantIdAndStatusIn(tenantId, ACTIVE_STATUSES);
        int limit = quota.maxConcurrentExecutions();
        if (current >= limit) {
            throw new QuotaExceededException("max_concurrent_executions", current, limit);
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Returns the quota for the given tenant, using a 60-second in-memory cache.
     * Falls back to defaults if the identity-service cannot be reached.
     */
    TenantQuotaResponse getQuota(UUID tenantId) {
        CacheEntry entry = cache.get(tenantId);
        if (entry != null && !entry.isExpired()) {
            return entry.quota();
        }

        TenantQuotaResponse quota = fetchFromIdentityService(tenantId);
        cache.put(tenantId, new CacheEntry(quota, Instant.now()));
        return quota;
    }

    private TenantQuotaResponse fetchFromIdentityService(UUID tenantId) {
        try {
            TenantQuotaResponse response = restClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/quotas", tenantId)
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .body(TenantQuotaResponse.class);
            if (response != null) {
                return response;
            }
            log.warn("Identity-service returned null quota for tenant {}; using defaults", tenantId);
        } catch (Exception e) {
            log.warn("Failed to fetch quota for tenant {} from identity-service: {}; using defaults",
                    tenantId, e.getMessage());
        }
        return defaults(tenantId);
    }

    private TenantQuotaResponse defaults(UUID tenantId) {
        return new TenantQuotaResponse(
                tenantId,
                defaultMaxDefinitions,
                defaultMaxExecutionsPerMinute,
                defaultMaxConcurrentExecutions,
                defaultMaxExecutionsPerMinute);
    }

    /** Exposed for tests: evict a specific tenant from the cache. */
    public void evictCache(UUID tenantId) {
        cache.remove(tenantId);
    }
}
