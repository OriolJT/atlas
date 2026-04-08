package com.atlas.common.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionIdTest {

    @Test
    void createFromUuid() {
        UUID uuid = UUID.randomUUID();
        ExecutionId id = new ExecutionId(uuid);
        assertEquals(uuid, id.value());
    }

    @Test
    void generateCreatesNonNullValue() {
        ExecutionId id = ExecutionId.generate();
        assertNotNull(id);
        assertNotNull(id.value());
    }

    @Test
    void generateCreatesDifferentIds() {
        ExecutionId id1 = ExecutionId.generate();
        ExecutionId id2 = ExecutionId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void createFromString() {
        UUID uuid = UUID.randomUUID();
        ExecutionId id = ExecutionId.fromString(uuid.toString());
        assertEquals(uuid, id.value());
    }

    @Test
    void rejectNullUuid() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionId(null));
        assertTrue(ex.getMessage().contains("ExecutionId value must not be null"));
    }

    @Test
    void rejectNullString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExecutionId.fromString(null));
        assertTrue(ex.getMessage().contains("ExecutionId string must not be null"));
    }

    @Test
    void rejectInvalidString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExecutionId.fromString("not-a-uuid"));
        assertTrue(ex.getMessage().contains("Invalid ExecutionId format"));
    }

    @Test
    void equalityWhenSameUuid() {
        UUID uuid = UUID.randomUUID();
        ExecutionId id1 = new ExecutionId(uuid);
        ExecutionId id2 = new ExecutionId(uuid);
        assertEquals(id1, id2);
    }

    @Test
    void inequalityWhenDifferentUuid() {
        ExecutionId id1 = ExecutionId.generate();
        ExecutionId id2 = ExecutionId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void toStringReturnsUuidString() {
        UUID uuid = UUID.randomUUID();
        ExecutionId id = new ExecutionId(uuid);
        assertEquals(uuid.toString(), id.toString());
    }
}
