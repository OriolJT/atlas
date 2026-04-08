package com.atlas.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void usesExistingCorrelationIdHeader() throws ServletException, IOException {
        request.addHeader("X-Correlation-ID", "existing-corr-id");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("existing-corr-id", request.getAttribute("correlationId"));
        assertEquals("existing-corr-id", response.getHeader("X-Correlation-ID"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderMissing() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        String correlationId = (String) request.getAttribute("correlationId");
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());
        assertEquals(correlationId, response.getHeader("X-Correlation-ID"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() throws ServletException, IOException {
        request.addHeader("X-Correlation-ID", "   ");

        filter.doFilterInternal(request, response, filterChain);

        String correlationId = (String) request.getAttribute("correlationId");
        assertNotNull(correlationId);
        assertFalse(correlationId.isBlank());
        assertNotEquals("   ", correlationId);
        assertEquals(correlationId, response.getHeader("X-Correlation-ID"));
    }

    @Test
    void setsCorrelationIdAsRequestAttribute() throws ServletException, IOException {
        request.addHeader("X-Correlation-ID", "test-id");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("test-id", request.getAttribute("correlationId"));
    }

    @Test
    void setsCorrelationIdAsResponseHeader() throws ServletException, IOException {
        request.addHeader("X-Correlation-ID", "test-id");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("test-id", response.getHeader("X-Correlation-ID"));
    }

    @Test
    void callsFilterChain() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void generatedIdIsValidUuid() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        String correlationId = (String) request.getAttribute("correlationId");
        assertDoesNotThrow(() -> java.util.UUID.fromString(correlationId));
    }

    @Test
    void differentRequestsGetDifferentGeneratedIds() throws ServletException, IOException {
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        MockHttpServletResponse response2 = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request2, response2, filterChain);

        String id1 = (String) request.getAttribute("correlationId");
        String id2 = (String) request2.getAttribute("correlationId");
        assertNotEquals(id1, id2);
    }
}
