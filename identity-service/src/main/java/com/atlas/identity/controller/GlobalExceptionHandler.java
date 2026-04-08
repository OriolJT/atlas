package com.atlas.identity.controller;

import com.atlas.common.web.ErrorResponse;
import com.atlas.common.web.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        var error = ErrorResponse.of("ATLAS-IDENTITY-001", ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
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
