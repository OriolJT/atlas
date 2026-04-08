package com.atlas.common.domain;

import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        if (value == null) {
            throw new IllegalArgumentException("TenantId value must not be null");
        }
    }

    public static TenantId generate() {
        return new TenantId(UUID.randomUUID());
    }

    public static TenantId fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TenantId string must not be null");
        }
        try {
            return new TenantId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid TenantId format: " + value, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
