package com.atlas.common.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserIdTest {

    @Test
    void createFromUuid() {
        UUID uuid = UUID.randomUUID();
        UserId id = new UserId(uuid);
        assertEquals(uuid, id.value());
    }

    @Test
    void generateCreatesNonNullValue() {
        UserId id = UserId.generate();
        assertNotNull(id);
        assertNotNull(id.value());
    }

    @Test
    void generateCreatesDifferentIds() {
        UserId id1 = UserId.generate();
        UserId id2 = UserId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void createFromString() {
        UUID uuid = UUID.randomUUID();
        UserId id = UserId.fromString(uuid.toString());
        assertEquals(uuid, id.value());
    }

    @Test
    void rejectNullUuid() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new UserId(null));
        assertTrue(ex.getMessage().contains("UserId value must not be null"));
    }

    @Test
    void rejectNullString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> UserId.fromString(null));
        assertTrue(ex.getMessage().contains("UserId string must not be null"));
    }

    @Test
    void rejectInvalidString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> UserId.fromString("not-a-uuid"));
        assertTrue(ex.getMessage().contains("Invalid UserId format"));
    }

    @Test
    void equalityWhenSameUuid() {
        UUID uuid = UUID.randomUUID();
        UserId id1 = new UserId(uuid);
        UserId id2 = new UserId(uuid);
        assertEquals(id1, id2);
    }

    @Test
    void inequalityWhenDifferentUuid() {
        UserId id1 = UserId.generate();
        UserId id2 = UserId.generate();
        assertNotEquals(id1, id2);
    }

    @Test
    void toStringReturnsUuidString() {
        UUID uuid = UUID.randomUUID();
        UserId id = new UserId(uuid);
        assertEquals(uuid.toString(), id.toString());
    }
}
