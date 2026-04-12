package com.atlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID tenantId,
        UUID correlationId,
        UUID causationId,
        String idempotencyKey,
        String payload
) {
    public DomainEvent {
        if (eventId == null) throw new IllegalArgumentException("eventId must not be null");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType must not be null or blank");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt must not be null");
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
    }

    public static DomainEvent create(String eventType, UUID tenantId, UUID correlationId, UUID causationId, String payload) {
        return new DomainEvent(UUID.randomUUID(), eventType, Instant.now(), tenantId, correlationId, causationId, UUID.randomUUID().toString(), payload);
    }

    public static DomainEvent create(String eventType, UUID tenantId, UUID correlationId, UUID causationId, String idempotencyKey, String payload) {
        return new DomainEvent(UUID.randomUUID(), eventType, Instant.now(), tenantId, correlationId, causationId, idempotencyKey, payload);
    }
}
