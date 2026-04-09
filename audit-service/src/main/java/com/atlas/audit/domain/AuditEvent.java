package com.atlas.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of a single audit event. All columns are non-updatable to enforce immutability.
 * The audit_event_id is provided by the event producer — not auto-generated here.
 */
@Entity
@Table(name = "audit_events", schema = "audit")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class AuditEvent {

    @Id
    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "actor_type", nullable = false, updatable = false, length = 50)
    private String actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(UUID auditEventId,
                      UUID tenantId,
                      String actorType,
                      UUID actorId,
                      String eventType,
                      String resourceType,
                      UUID resourceId,
                      Map<String, Object> payload,
                      UUID correlationId,
                      Instant occurredAt) {
        this.auditEventId = auditEventId;
        this.tenantId = tenantId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload != null ? payload : Map.of();
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getAuditEventId() {
        return auditEventId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
