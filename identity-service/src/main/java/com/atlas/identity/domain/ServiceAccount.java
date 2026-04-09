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
@Table(name = "service_accounts", schema = "identity")
public class ServiceAccount {

    public enum Status {
        ACTIVE, DISABLED
    }

    @Id
    @Column(name = "service_account_id", nullable = false, updatable = false)
    private UUID serviceAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ServiceAccount() {
        // JPA
    }

    public ServiceAccount(UUID tenantId, String name) {
        this.serviceAccountId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.status = Status.ACTIVE;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getServiceAccountId() {
        return serviceAccountId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
