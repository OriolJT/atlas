package com.atlas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class OutboxEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    private OutboxEvent(String aggregateType, UUID aggregateId, String eventType,
                        String topic, Map<String, Object> payload, UUID tenantId) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.tenantId = tenantId;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent create(String aggregateType, UUID aggregateId, String eventType,
                                     String topic, Map<String, Object> payload, UUID tenantId) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, topic, payload, tenantId);
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    // --- Getters ---

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
