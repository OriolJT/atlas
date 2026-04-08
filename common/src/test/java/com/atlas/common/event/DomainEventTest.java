package com.atlas.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DomainEventTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "tenant.created";
    private static final Instant OCCURRED_AT = Instant.now();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID CAUSATION_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = UUID.randomUUID().toString();
    private static final String PAYLOAD = "{\"key\":\"value\"}";

    @Test
    void createWithAllFields() {
        DomainEvent event = new DomainEvent(EVENT_ID, EVENT_TYPE, OCCURRED_AT, TENANT_ID,
                CORRELATION_ID, CAUSATION_ID, IDEMPOTENCY_KEY, PAYLOAD);

        assertEquals(EVENT_ID, event.eventId());
        assertEquals(EVENT_TYPE, event.eventType());
        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(TENANT_ID, event.tenantId());
        assertEquals(CORRELATION_ID, event.correlationId());
        assertEquals(CAUSATION_ID, event.causationId());
        assertEquals(IDEMPOTENCY_KEY, event.idempotencyKey());
        assertEquals(PAYLOAD, event.payload());
    }

    @Test
    void factoryMethodPopulatesGeneratedFields() {
        Instant before = Instant.now();
        DomainEvent event = DomainEvent.create(EVENT_TYPE, TENANT_ID, CORRELATION_ID, CAUSATION_ID, PAYLOAD);
        Instant after = Instant.now();

        assertNotNull(event.eventId());
        assertEquals(EVENT_TYPE, event.eventType());
        assertFalse(event.occurredAt().isBefore(before));
        assertFalse(event.occurredAt().isAfter(after));
        assertEquals(TENANT_ID, event.tenantId());
        assertEquals(CORRELATION_ID, event.correlationId());
        assertEquals(CAUSATION_ID, event.causationId());
        assertNotNull(event.idempotencyKey());
        assertFalse(event.idempotencyKey().isBlank());
        assertEquals(PAYLOAD, event.payload());
    }

    @Test
    void factoryMethodGeneratesUniqueEventIds() {
        DomainEvent e1 = DomainEvent.create(EVENT_TYPE, TENANT_ID, null, null, null);
        DomainEvent e2 = DomainEvent.create(EVENT_TYPE, TENANT_ID, null, null, null);
        assertNotEquals(e1.eventId(), e2.eventId());
    }

    @Test
    void nullEventIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new DomainEvent(null, EVENT_TYPE, OCCURRED_AT, TENANT_ID, null, null, IDEMPOTENCY_KEY, null));
    }

    @Test
    void nullEventTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new DomainEvent(EVENT_ID, null, OCCURRED_AT, TENANT_ID, null, null, IDEMPOTENCY_KEY, null));
    }

    @Test
    void blankEventTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new DomainEvent(EVENT_ID, "  ", OCCURRED_AT, TENANT_ID, null, null, IDEMPOTENCY_KEY, null));
    }

    @Test
    void nullOccurredAtThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new DomainEvent(EVENT_ID, EVENT_TYPE, null, TENANT_ID, null, null, IDEMPOTENCY_KEY, null));
    }

    @Test
    void nullTenantIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new DomainEvent(EVENT_ID, EVENT_TYPE, OCCURRED_AT, null, null, null, IDEMPOTENCY_KEY, null));
    }

    @Test
    void optionalFieldsAllowNull() {
        assertDoesNotThrow(() ->
                new DomainEvent(EVENT_ID, EVENT_TYPE, OCCURRED_AT, TENANT_ID, null, null, null, null));
    }
}
