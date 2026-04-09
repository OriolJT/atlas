package com.atlas.identity.dto;

import com.atlas.identity.domain.ApiKey;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID apiKeyId,
        UUID serviceAccountId,
        UUID tenantId,
        String keyPrefix,
        String status,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        String rawKey) {

    public static ApiKeyResponse from(ApiKey apiKey, String rawKey) {
        return new ApiKeyResponse(
                apiKey.getApiKeyId(),
                apiKey.getServiceAccountId(),
                apiKey.getTenantId(),
                apiKey.getKeyPrefix(),
                apiKey.getStatus().name(),
                apiKey.getCreatedAt(),
                apiKey.getExpiresAt(),
                apiKey.getLastUsedAt(),
                rawKey);
    }

    public static ApiKeyResponse from(ApiKey apiKey) {
        return from(apiKey, null);
    }
}
