package com.atlas.common.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorResponseTest {

    @Test
    void ofCreatesResponseWithCodeMessageAndCorrelationId() {
        Instant before = Instant.now();
        ErrorResponse response = ErrorResponse.of("ATLAS-001", "Something went wrong", "corr-123");
        Instant after = Instant.now();

        assertEquals("ATLAS-001", response.code());
        assertEquals("Something went wrong", response.message());
        assertEquals("corr-123", response.correlationId());
        assertNull(response.details());
        assertNull(response.errors());
        assertNotNull(response.timestamp());
        assertFalse(response.timestamp().isBefore(before));
        assertFalse(response.timestamp().isAfter(after));
    }

    @Test
    void withDetailsIncludesDetailsField() {
        ErrorResponse response = ErrorResponse.withDetails("ATLAS-002", "Not found", "Resource X does not exist", "corr-456");

        assertEquals("ATLAS-002", response.code());
        assertEquals("Not found", response.message());
        assertEquals("Resource X does not exist", response.details());
        assertEquals("corr-456", response.correlationId());
        assertNull(response.errors());
    }

    @Test
    void validationErrorUsesAtlasCommon001Code() {
        List<FieldError> fieldErrors = List.of(
                new FieldError("email", "must not be blank"),
                new FieldError("name", "size must be between 1 and 100"));

        ErrorResponse response = ErrorResponse.validationError(fieldErrors, "corr-789");

        assertEquals("ATLAS-COMMON-001", response.code());
        assertEquals("Validation failed", response.message());
        assertEquals(fieldErrors, response.errors());
        assertEquals("corr-789", response.correlationId());
        assertNull(response.details());
    }

    @Test
    void validationErrorWithEmptyFieldErrorsList() {
        ErrorResponse response = ErrorResponse.validationError(List.of(), "corr-000");

        assertEquals("ATLAS-COMMON-001", response.code());
        assertTrue(response.errors().isEmpty());
    }

    @Test
    void directConstructionPreservesAllFields() {
        Instant ts = Instant.parse("2026-01-15T10:00:00Z");
        List<FieldError> errors = List.of(new FieldError("field", "msg"));

        ErrorResponse response = new ErrorResponse("CODE", "msg", "details", errors, "corr", ts);

        assertEquals("CODE", response.code());
        assertEquals("msg", response.message());
        assertEquals("details", response.details());
        assertEquals(errors, response.errors());
        assertEquals("corr", response.correlationId());
        assertEquals(ts, response.timestamp());
    }

    @Test
    void timestampIsSetAtCreationTime() throws InterruptedException {
        Instant before = Instant.now();
        Thread.sleep(1);
        ErrorResponse response = ErrorResponse.of("CODE", "msg", "corr");
        Thread.sleep(1);
        Instant after = Instant.now();

        assertTrue(response.timestamp().isAfter(before));
        assertTrue(response.timestamp().isBefore(after));
    }
}
