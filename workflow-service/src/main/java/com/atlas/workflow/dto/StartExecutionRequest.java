package com.atlas.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record StartExecutionRequest(
        @NotNull(message = "definitionId is required")
        UUID definitionId,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        Map<String, Object> input
) {}
