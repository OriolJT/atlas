package com.atlas.workflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateDefinitionRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotNull(message = "version is required")
        @Min(value = 1, message = "version must be at least 1")
        Integer version,

        Object steps,

        Map<String, Object> compensations,

        String triggerType
) {}
