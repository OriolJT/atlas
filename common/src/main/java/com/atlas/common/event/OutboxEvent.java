package com.atlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id, String aggregateType, UUID aggregateId, String eventType,
        String topic, String payload, UUID tenantId, Instant createdAt, Instant publishedAt
) {
    public OutboxEvent {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (aggregateType == null || aggregateType.isBlank()) throw new IllegalArgumentException("aggregateType must not be null or blank");
        if (aggregateId == null) throw new IllegalArgumentException("aggregateId must not be null");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType must not be null or blank");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic must not be null or blank");
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    }

    public static OutboxEvent create(String aggregateType, UUID aggregateId, String eventType, String topic, String payload, UUID tenantId) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, eventType, topic, payload, tenantId, Instant.now(), null);
    }

    public OutboxEvent markPublished() {
        return new OutboxEvent(id, aggregateType, aggregateId, eventType, topic, payload, tenantId, createdAt, Instant.now());
    }

    public boolean isPublished() { return publishedAt != null; }
}
