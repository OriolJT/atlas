package com.atlas.workflow.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SignalRequest(
        @NotBlank String stepName,
        Map<String, Object> payload
) {}
