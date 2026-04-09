package com.atlas.workflow.controller;

import com.atlas.common.web.ErrorResponse;
import com.atlas.common.web.FieldError;
import com.atlas.workflow.exception.ConflictException;
import com.atlas.workflow.exception.QuotaExceededException;
import com.atlas.workflow.exception.ResourceNotFoundException;
import com.atlas.workflow.exception.UnprocessableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        var error = ErrorResponse.of("ATLAS-WORKFLOW-003", ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        var error = ErrorResponse.of("ATLAS-WORKFLOW-004", ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<ErrorResponse> handleUnprocessable(UnprocessableException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        var error = ErrorResponse.of("ATLAS-WORKFLOW-005", ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        String details = "quota=" + ex.getQuotaType()
                + ", current=" + ex.getCurrent()
                + ", limit=" + ex.getLimit();
        var error = ErrorResponse.withDetails("ATLAS-WF-008", ex.getMessage(), details, correlationId);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        var error = ErrorResponse.of("ATLAS-WORKFLOW-001", ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        var error = ErrorResponse.validationError(fieldErrors, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    private String getCorrelationId(HttpServletRequest request) {
        Object correlationId = request.getAttribute("correlationId");
        return correlationId != null ? correlationId.toString() : null;
    }
}
