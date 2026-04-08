package com.atlas.common.web;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        String details,
        List<FieldError> errors,
        String correlationId,
        Instant timestamp) {

    public static ErrorResponse of(String code, String message, String correlationId) {
        return new ErrorResponse(code, message, null, null, correlationId, Instant.now());
    }

    public static ErrorResponse withDetails(String code, String message, String details, String correlationId) {
        return new ErrorResponse(code, message, details, null, correlationId, Instant.now());
    }

    public static ErrorResponse validationError(List<FieldError> errors, String correlationId) {
        return new ErrorResponse("ATLAS-COMMON-001", "Validation failed", null, errors, correlationId, Instant.now());
    }
}
