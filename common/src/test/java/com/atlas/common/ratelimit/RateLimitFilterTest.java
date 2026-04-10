package com.atlas.common.ratelimit;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimiter rateLimiter;
    private RateLimitProperties properties;
    private TenantQuotaResolver quotaResolver;
    private ObjectMapper objectMapper;
    private RateLimitFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        properties = new RateLimitProperties();
        properties.setRequestsPerMinute(60);
        properties.setBurstSize(10);
        quotaResolver = new DefaultTenantQuotaResolver();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        filter = new RateLimitFilter(rateLimiter, properties, quotaResolver, objectMapper);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequest_whenBucketHasTokens() throws ServletException, IOException {
        UUID tenantId = UUID.randomUUID();
        setAuthenticatedPrincipal(tenantId);
        when(rateLimiter.tryConsume(eq(tenantId), eq(60), eq(10))).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void returns429_whenBucketIsEmpty() throws ServletException, IOException {
        UUID tenantId = UUID.randomUUID();
        setAuthenticatedPrincipal(tenantId);
        request.setAttribute("correlationId", "test-corr-id");
        when(rateLimiter.tryConsume(eq(tenantId), eq(60), eq(10))).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("ATLAS-COMMON-003");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void passesThrough_whenNoAuthentication() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void skipsActuatorEndpoints() {
        request.setServletPath("/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void skipsSwaggerEndpoints() {
        request.setServletPath("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void skipsApiDocsEndpoints() {
        request.setServletPath("/v3/api-docs");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void skipsAuthEndpoints() {
        request.setServletPath("/api/v1/auth/login");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void doesNotSkipApiEndpoints() {
        request.setServletPath("/api/v1/workflows");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void extractsTenantId_fromRequestAttribute() throws ServletException, IOException {
        UUID tenantId = UUID.randomUUID();
        request.setAttribute("rateLimitTenantId", tenantId);
        when(rateLimiter.tryConsume(eq(tenantId), anyInt(), anyInt())).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimiter).tryConsume(eq(tenantId), eq(60), eq(10));
        verify(filterChain).doFilter(request, response);
    }

    private void setAuthenticatedPrincipal(UUID tenantId) {
        var principal = new AuthenticatedPrincipal(UUID.randomUUID(), tenantId, List.of("USER"));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
