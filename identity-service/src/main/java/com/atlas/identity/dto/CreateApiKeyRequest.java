package com.atlas.identity.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyRequest(
        @NotNull(message = "Service account ID is required")
        UUID serviceAccountId,

        Instant expiresAt) {
}
