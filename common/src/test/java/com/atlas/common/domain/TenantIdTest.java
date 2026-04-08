package com.atlas.common.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenantIdTest {

    @Test
    void createFromUuid() {
        UUID uuid = UUID.randomUUID();
        TenantId id = new TenantId(uuid);
        assertEquals(uuid, id.value());
    }

    @Test
    void generateCreatesNonNullValue() {
        TenantId id = TenantId.generate();
        assertNotNull(id);
        assertNotNull(id.value());
    }

    @Test
    void generateCreatesDifferentIds() {
        TenantId id1 = TenantId.generate();
        TenantId id2 = TenantId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void createFromString() {
        UUID uuid = UUID.randomUUID();
        TenantId id = TenantId.fromString(uuid.toString());
        assertEquals(uuid, id.value());
    }

    @Test
    void rejectNullUuid() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TenantId(null));
        assertTrue(ex.getMessage().contains("TenantId value must not be null"));
    }

    @Test
    void rejectNullString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TenantId.fromString(null));
        assertTrue(ex.getMessage().contains("TenantId string must not be null"));
    }

    @Test
    void rejectInvalidString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TenantId.fromString("not-a-uuid"));
        assertTrue(ex.getMessage().contains("Invalid TenantId format"));
    }

    @Test
    void equalityWhenSameUuid() {
        UUID uuid = UUID.randomUUID();
        TenantId id1 = new TenantId(uuid);
        TenantId id2 = new TenantId(uuid);
        assertEquals(id1, id2);
    }

    @Test
    void inequalityWhenDifferentUuid() {
        TenantId id1 = TenantId.generate();
        TenantId id2 = TenantId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void toStringReturnsUuidString() {
        UUID uuid = UUID.randomUUID();
        TenantId id = new TenantId(uuid);
        assertEquals(uuid.toString(), id.toString());
    }
}
