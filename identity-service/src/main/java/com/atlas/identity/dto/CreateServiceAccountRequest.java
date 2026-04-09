package com.atlas.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateServiceAccountRequest(
        @NotNull(message = "Tenant ID is required")
        UUID tenantId,

        @NotBlank(message = "Name is required")
        String name) {
}
