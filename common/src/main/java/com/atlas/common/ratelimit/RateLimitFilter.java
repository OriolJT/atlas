package com.atlas.common.ratelimit;

import com.atlas.common.web.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Servlet filter that enforces per-tenant rate limits via a Redis token bucket.
 *
 * <p>Registered by each service's {@code SecurityConfig} after the JWT auth filter so that
 * tenant context is available. Skips non-tenant paths (actuator, swagger, auth endpoints).
 *
 * <p>When the bucket is exhausted the filter writes a 429 response and sets
 * {@code Retry-After: 60} (seconds until next full minute refill window).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final TenantQuotaResolver quotaResolver;
    private final ObjectMapper objectMapper;
    private final SecretKey jwtSigningKey;

    public RateLimitFilter(RateLimiter rateLimiter,
                           RateLimitProperties properties,
                           TenantQuotaResolver quotaResolver,
                           ObjectMapper objectMapper,
                           String jwtSecret) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.quotaResolver = quotaResolver;
        this.objectMapper = objectMapper;
        this.jwtSigningKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        UUID tenantId = extractTenantId(request);

        if (tenantId == null) {
            // No authenticated tenant -- pass through (auth filter will handle 401)
            filterChain.doFilter(request, response);
            return;
        }

        int rpm = quotaResolver.resolveRequestsPerMinute(tenantId, properties.getRequestsPerMinute());
        int burst = properties.getBurstSize();

        if (rateLimiter.tryConsume(tenantId, rpm, burst)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for tenant {}", tenantId);
            rejectTooManyRequests(request, response, tenantId);
        }
    }

    private UUID extractTenantId(HttpServletRequest request) {
        // Try Bearer JWT
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(jwtSigningKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                String tenantIdStr = claims.get("tenant_id", String.class);
                if (tenantIdStr != null) {
                    return UUID.fromString(tenantIdStr);
                }
            } catch (ExpiredJwtException e) {
                String tenantIdStr = e.getClaims().get("tenant_id", String.class);
                if (tenantIdStr != null) {
                    return UUID.fromString(tenantIdStr);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // Malformed -- let auth filter deal with it
            }
        }
        // X-API-Key is handled by ApiKeyAuthenticationFilter; at the point the rate
        // limit filter runs (after ApiKeyAuthenticationFilter) the tenant is in the
        // request attribute set by ApiKeyAuthenticationFilter.
        Object attr = request.getAttribute("rateLimitTenantId");
        if (attr instanceof UUID tenantIdAttr) {
            return tenantIdAttr;
        }
        return null;
    }

    private void rejectTooManyRequests(HttpServletRequest request,
                                       HttpServletResponse response,
                                       UUID tenantId) throws IOException {
        String correlationId = (String) request.getAttribute("correlationId");
        if (correlationId == null) {
            correlationId = "unknown";
        }
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        ErrorResponse body = ErrorResponse.withDetails(
                "ATLAS-COMMON-003",
                "Rate limit exceeded",
                "Tenant " + tenantId + " has exceeded its API request quota. Retry after 60 seconds.",
                correlationId
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api/v1/auth/");
    }
}
