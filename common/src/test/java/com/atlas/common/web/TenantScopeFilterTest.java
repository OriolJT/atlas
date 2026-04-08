package com.atlas.common.web;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantScopeFilterTest {

    private TenantContext tenantContext;
    private TenantScopeFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private AuthenticatedPrincipal principal;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        filter = new TenantScopeFilter(tenantContext);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        principal = new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), List.of("user"));
    }

    @Test
    void setsTenantContextWhenPrincipalPresent() throws ServletException, IOException {
        request.setAttribute("authenticatedPrincipal", principal);
        FilterChain filterChain = (req, res) -> {
            assertTrue(tenantContext.isAuthenticated());
            assertEquals(principal, tenantContext.getPrincipal());
        };

        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void clearsTenantContextAfterRequest() throws ServletException, IOException {
        request.setAttribute("authenticatedPrincipal", principal);
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        assertFalse(tenantContext.isAuthenticated());
    }

    @Test
    void clearsTenantContextOnException() throws ServletException, IOException {
        request.setAttribute("authenticatedPrincipal", principal);
        FilterChain filterChain = (req, res) -> {
            throw new ServletException("simulated error");
        };

        assertThrows(ServletException.class, () -> filter.doFilterInternal(request, response, filterChain));

        assertFalse(tenantContext.isAuthenticated());
    }

    @Test
    void doesNotSetContextWhenNoPrincipal() throws ServletException, IOException {
        FilterChain filterChain = (req, res) -> assertFalse(tenantContext.isAuthenticated());

        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void doesNotSetContextWhenAttributeIsWrongType() throws ServletException, IOException {
        request.setAttribute("authenticatedPrincipal", "not-a-principal");
        FilterChain filterChain = (req, res) -> assertFalse(tenantContext.isAuthenticated());

        filter.doFilterInternal(request, response, filterChain);
    }

    @Test
    void clearsTenantContextAfterRequestWithNoPrincipal() throws ServletException, IOException {
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        assertFalse(tenantContext.isAuthenticated());
    }

    @Test
    void callsFilterChain() throws ServletException, IOException {
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
