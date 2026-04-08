package com.atlas.common.domain;

import java.util.UUID;

public record UserId(UUID value) {

    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("UserId value must not be null");
        }
    }

    public static UserId generate() {
        return new UserId(UUID.randomUUID());
    }

    public static UserId fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("UserId string must not be null");
        }
        try {
            return new UserId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UserId format: " + value, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
