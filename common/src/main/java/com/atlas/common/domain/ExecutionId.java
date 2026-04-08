package com.atlas.common.domain;

import java.util.UUID;

public record ExecutionId(UUID value) {

    public ExecutionId {
        if (value == null) {
            throw new IllegalArgumentException("ExecutionId value must not be null");
        }
    }

    public static ExecutionId generate() {
        return new ExecutionId(UUID.randomUUID());
    }

    public static ExecutionId fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ExecutionId string must not be null");
        }
        try {
            return new ExecutionId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid ExecutionId format: " + value, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
