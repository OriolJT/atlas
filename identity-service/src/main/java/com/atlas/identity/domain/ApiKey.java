package com.atlas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
@Table(name = "api_keys", schema = "identity")
public class ApiKey {

    public enum Status {
        ACTIVE, REVOKED
    }

    @Id
    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    @Column(name = "service_account_id", nullable = false, updatable = false)
    private UUID serviceAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, updatable = false, length = 8)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {
        // JPA
    }

    public ApiKey(UUID serviceAccountId, UUID tenantId, String keyHash, String keyPrefix, Instant expiresAt) {
        this.apiKeyId = UUID.randomUUID();
        this.serviceAccountId = serviceAccountId;
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.status = Status.ACTIVE;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return status == Status.ACTIVE && !isExpired();
    }

    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }

    public void revoke() {
        this.status = Status.REVOKED;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public UUID getServiceAccountId() {
        return serviceAccountId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
