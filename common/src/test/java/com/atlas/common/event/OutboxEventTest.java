package com.atlas.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxEventTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String AGGREGATE_TYPE = "Tenant";
    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "tenant.created";
    private static final String TOPIC = "domain.events";
    private static final String PAYLOAD = "{\"tenantId\":\"abc\"}";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.now();

    @Test
    void createWithAllFields() {
        OutboxEvent event = new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE,
                TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null);

        assertEquals(ID, event.id());
        assertEquals(AGGREGATE_TYPE, event.aggregateType());
        assertEquals(AGGREGATE_ID, event.aggregateId());
        assertEquals(EVENT_TYPE, event.eventType());
        assertEquals(TOPIC, event.topic());
        assertEquals(PAYLOAD, event.payload());
        assertEquals(TENANT_ID, event.tenantId());
        assertEquals(CREATED_AT, event.createdAt());
        assertNull(event.publishedAt());
    }

    @Test
    void factoryMethodPopulatesGeneratedFields() {
        Instant before = Instant.now();
        OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID);
        Instant after = Instant.now();

        assertNotNull(event.id());
        assertEquals(AGGREGATE_TYPE, event.aggregateType());
        assertEquals(AGGREGATE_ID, event.aggregateId());
        assertEquals(EVENT_TYPE, event.eventType());
        assertEquals(TOPIC, event.topic());
        assertEquals(PAYLOAD, event.payload());
        assertEquals(TENANT_ID, event.tenantId());
        assertFalse(event.createdAt().isBefore(before));
        assertFalse(event.createdAt().isAfter(after));
        assertNull(event.publishedAt());
    }

    @Test
    void isPublishedFalseWhenPublishedAtIsNull() {
        OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID);
        assertFalse(event.isPublished());
    }

    @Test
    void markPublishedSetsPublishedAt() {
        OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID);
        Instant before = Instant.now();
        OutboxEvent published = event.markPublished();
        Instant after = Instant.now();

        assertTrue(published.isPublished());
        assertNotNull(published.publishedAt());
        assertFalse(published.publishedAt().isBefore(before));
        assertFalse(published.publishedAt().isAfter(after));
    }

    @Test
    void markPublishedReturnsNewInstancePreservingFields() {
        OutboxEvent original = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID);
        OutboxEvent published = original.markPublished();

        assertNotSame(original, published);
        assertEquals(original.id(), published.id());
        assertEquals(original.aggregateType(), published.aggregateType());
        assertEquals(original.aggregateId(), published.aggregateId());
        assertEquals(original.eventType(), published.eventType());
        assertEquals(original.topic(), published.topic());
        assertEquals(original.payload(), published.payload());
        assertEquals(original.tenantId(), published.tenantId());
        assertEquals(original.createdAt(), published.createdAt());
        assertFalse(original.isPublished());
    }

    @Test
    void nullIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(null, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void nullAggregateTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, null, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void blankAggregateTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, "", AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void nullAggregateIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, null, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void nullEventTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, null, TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void blankEventTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, "   ", TOPIC, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void nullTopicThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, null, PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void blankTopicThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "  ", PAYLOAD, TENANT_ID, CREATED_AT, null));
    }

    @Test
    void nullTenantIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, null, CREATED_AT, null));
    }

    @Test
    void nullCreatedAtThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, PAYLOAD, TENANT_ID, null, null));
    }

    @Test
    void nullPayloadAllowed() {
        assertDoesNotThrow(() ->
                new OutboxEvent(ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, TOPIC, null, TENANT_ID, CREATED_AT, null));
    }
}
