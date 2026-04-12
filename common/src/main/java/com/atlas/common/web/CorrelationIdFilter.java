package com.atlas.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String ATTRIBUTE_NAME = "correlationId";

    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[a-zA-Z0-9\\-]+$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER_NAME);
        if (!isValidCorrelationId(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(ATTRIBUTE_NAME, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put("correlationId", correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static boolean isValidCorrelationId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.length() > MAX_CORRELATION_ID_LENGTH) {
            return false;
        }
        return VALID_CORRELATION_ID.matcher(value).matches();
    }
}
