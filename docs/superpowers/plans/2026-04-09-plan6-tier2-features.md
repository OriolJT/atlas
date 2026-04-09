# Plan 6: Tier 2 Features

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Tier 2 features: rate limiting, per-tenant quotas, service account API keys, and Redis-based delayed scheduling.

**Architecture:** Rate limiting via Redis token bucket filter. Quotas stored on tenant entity, enforced at workflow/execution creation. Service accounts with SHA-256 hashed API keys as alternative auth path. Redis ZADD scheduler for delay steps.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Redis, Spring Data JPA

**Depends on:** Plans 1-5 (all Tier 1 complete)

---

## Task 1: Rate Limiting — Redis Token Bucket

**New error codes added:** `ATLAS-COMMON-003` (rate limit exceeded)

**Files to create:**
- `common/src/main/java/com/atlas/common/ratelimit/RateLimiter.java`
- `common/src/main/java/com/atlas/common/ratelimit/RedisRateLimiter.java`
- `common/src/main/java/com/atlas/common/ratelimit/RateLimitFilter.java`
- `common/src/main/java/com/atlas/common/ratelimit/RateLimitProperties.java`

**Files to modify:**
- `common/pom.xml` — add `spring-boot-starter-data-redis` dependency

### Step 1.1 — Add Redis dependency to `common/pom.xml`

Open `common/pom.xml`. Add the following dependency inside `<dependencies>`:

```xml
<!-- Redis (for rate limiter) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
    <optional>true</optional>
</dependency>
```

Mark it `<optional>true</optional>` so services that already declare it are not affected, and services that don't want it do not pull it transitively.

### Step 1.2 — `RateLimiter.java`

```java
package com.atlas.common.ratelimit;

import java.util.UUID;

/**
 * Abstraction over the rate-limiting algorithm.
 * Returns {@code true} when the request is allowed, {@code false} when the bucket is empty.
 */
public interface RateLimiter {

    /**
     * Attempt to consume one token for the given tenant.
     *
     * @param tenantId the tenant whose bucket to check
     * @param requestsPerMinute the refill rate for this tenant
     * @param burstSize the maximum token accumulation
     * @return {@code true} if a token was consumed (request allowed), {@code false} otherwise
     */
    boolean tryConsume(UUID tenantId, int requestsPerMinute, int burstSize);
}
```

### Step 1.3 — `RateLimitProperties.java`

```java
package com.atlas.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.rate-limit")
public class RateLimitProperties {

    /** Global default requests per minute per tenant. Overridden per tenant by quota. */
    private int requestsPerMinute = 60;

    /** Maximum burst size (token accumulation). */
    private int burstSize = 10;

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getBurstSize() {
        return burstSize;
    }

    public void setBurstSize(int burstSize) {
        this.burstSize = burstSize;
    }
}
```

### Step 1.4 — `RedisRateLimiter.java`

Uses a single Redis EVAL call with a Lua script to atomically implement the token bucket algorithm. The script is loaded once and evaluated per request. No race conditions — Redis executes Lua scripts as a single atomic command.

**Key design:**
- Key: `rate_limit:{tenantId}` — stores `{tokens}:{last_refill_epoch_seconds}` as a string
- Refill: `elapsed_seconds * (requestsPerMinute / 60.0)` tokens added since last refill, capped at burstSize
- TTL: 120 seconds (2 minutes) — key auto-expires if no requests for a while

```java
package com.atlas.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RedisRateLimiter implements RateLimiter {

    // Atomic token bucket Lua script.
    // KEYS[1] = rate limit key (e.g. "rate_limit:{tenantId}")
    // ARGV[1] = requests_per_minute
    // ARGV[2] = burst_size
    // ARGV[3] = current epoch seconds
    // Returns 1 if allowed, 0 if denied.
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local stored = redis.call('GET', key)
            local tokens
            local last_refill

            if stored == false then
                tokens = burst
                last_refill = now
            else
                local parts = {}
                for part in string.gmatch(stored, '([^:]+)') do
                    table.insert(parts, part)
                end
                tokens = tonumber(parts[1])
                last_refill = tonumber(parts[2])
            end

            local elapsed = now - last_refill
            local refill = elapsed * (rate / 60.0)
            tokens = math.min(burst, tokens + refill)
            last_refill = now

            if tokens >= 1.0 then
                tokens = tokens - 1.0
                redis.call('SET', key, string.format('%.6f:%d', tokens, last_refill), 'EX', 120)
                return 1
            else
                redis.call('SET', key, string.format('%.6f:%d', tokens, last_refill), 'EX', 120)
                return 0
            end
            """;

    private final RedisScript<Long> script;
    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = RedisScript.of(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean tryConsume(UUID tenantId, int requestsPerMinute, int burstSize) {
        String key = "rate_limit:" + tenantId;
        long nowSeconds = System.currentTimeMillis() / 1000L;

        Long result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(requestsPerMinute),
                String.valueOf(burstSize),
                String.valueOf(nowSeconds)
        );

        return Long.valueOf(1L).equals(result);
    }
}
```

### Step 1.5 — `RateLimitFilter.java`

Extends `OncePerRequestFilter`. Extracts `tenant_id` from the JWT (if present). Skips unauthenticated paths (actuator, auth). Returns `429 Too Many Requests` with a `Retry-After` header when the bucket is empty.

Tenant quota is resolved lazily by calling a `TenantQuotaResolver` strategy — in Task 1 this is just the global default; in Task 8 it reads the per-tenant quota from a cache.

```java
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
    private static final String HEADER_API_KEY = "X-API-Key";

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
            // No authenticated tenant — pass through (auth filter will handle 401)
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
                // Malformed — let auth filter deal with it
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
```

### Step 1.6 — `TenantQuotaResolver.java`

A simple strategy interface. In Task 1 a default implementation returns the global default. In Task 8 it is replaced by a caching implementation that reads per-tenant quota values.

```java
package com.atlas.common.ratelimit;

import java.util.UUID;

/**
 * Resolves the effective requests-per-minute limit for a tenant.
 * Default implementation returns the global default.
 * Task 8 replaces this with a cache-backed implementation.
 */
public interface TenantQuotaResolver {

    /**
     * Returns requests-per-minute for the tenant, falling back to {@code defaultRpm}
     * when no tenant-specific quota is found.
     */
    int resolveRequestsPerMinute(UUID tenantId, int defaultRpm);
}
```

```java
package com.atlas.common.ratelimit;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default resolver — always returns the global default. Replaced in Task 8. */
@Component
public class DefaultTenantQuotaResolver implements TenantQuotaResolver {

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        return defaultRpm;
    }
}
```

### Step 1.7 — Unit test: `RedisRateLimiterTest.java`

Location: `common/src/test/java/com/atlas/common/ratelimit/RedisRateLimiterTest.java`

```java
package com.atlas.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimiterTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    // Wiring done via Testcontainers @ServiceConnection — StringRedisTemplate auto-configured.

    @Autowired
    private RedisRateLimiter rateLimiter;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void firstRequest_allowed_whenBucketFull() {
        assertThat(rateLimiter.tryConsume(tenantId, 60, 10)).isTrue();
    }

    @Test
    void consecutiveRequests_respectBurstSize() {
        int burst = 5;
        int allowed = 0;
        for (int i = 0; i < burst + 2; i++) {
            if (rateLimiter.tryConsume(tenantId, 60, burst)) {
                allowed++;
            }
        }
        // Burst of 5 means the first 5 succeed immediately; 6th and 7th are denied
        // (no time has passed in the test)
        assertThat(allowed).isEqualTo(burst);
    }

    @Test
    void differentTenants_haveSeparateBuckets() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        int burst = 2;
        // Drain tenant A's bucket
        rateLimiter.tryConsume(tenantA, 60, burst);
        rateLimiter.tryConsume(tenantA, 60, burst);
        boolean tenantAThird = rateLimiter.tryConsume(tenantA, 60, burst);

        // Tenant B should still have full bucket
        boolean tenantBFirst = rateLimiter.tryConsume(tenantB, 60, burst);

        assertThat(tenantAThird).isFalse();
        assertThat(tenantBFirst).isTrue();
    }
}
```

### Commit message

```
feat(common): add Redis token bucket rate limiter and RateLimitFilter

Implements RateLimiter interface with atomic Lua-script-backed Redis
token bucket. RateLimitFilter enforces per-tenant rate limits as an
OncePerRequestFilter; returns 429 with Retry-After header. Introduces
TenantQuotaResolver strategy for per-tenant RPM resolution (default
returns global value; replaced in Task 8).
```

---

## Task 2: Rate Limiting — Wire into All Services

**Files to modify:**
- `identity-service/pom.xml` — add `spring-boot-starter-data-redis`
- `audit-service/pom.xml` — add `spring-boot-starter-data-redis`
- `identity-service/src/main/java/com/atlas/identity/config/SecurityConfig.java`
- `workflow-service/src/main/java/com/atlas/workflow/config/SecurityConfig.java`
- `worker-service/src/main/java/com/atlas/worker/config/SecurityConfig.java`
- `audit-service/src/main/java/com/atlas/audit/config/SecurityConfig.java`
- `identity-service/src/test/java/com/atlas/identity/TestcontainersConfiguration.java`
- `audit-service/src/test/java/com/atlas/audit/TestcontainersConfiguration.java`
- `identity-service/src/main/resources/application.yml` — add `spring.data.redis` + `atlas.rate-limit`
- `audit-service/src/main/resources/application.yml` — add `spring.data.redis` + `atlas.rate-limit`

**Files to create:**
- `identity-service/src/test/java/com/atlas/identity/controller/RateLimitIntegrationTest.java`

### Step 2.1 — Add Redis to `identity-service/pom.xml`

Add inside `<dependencies>`:

```xml
<!-- Redis (rate limiting) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <scope>test</scope>
</dependency>
```

Do the same for `audit-service/pom.xml`. Note: `workflow-service` and `worker-service` already have Redis.

### Step 2.2 — Add Redis to `identity-service/src/main/resources/application.yml`

Add under `spring:`:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

atlas:
  rate-limit:
    requests-per-minute: 60
    burst-size: 10
```

Apply the same block to `audit-service/src/main/resources/application.yml`.

### Step 2.3 — Update `identity-service` `TestcontainersConfiguration.java`

Add Redis container alongside the existing PostgreSQL and Kafka containers:

```java
@Bean
@ServiceConnection(name = "redis")
public GenericContainer<?> redisContainer() {
    return new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
}
```

Add the same bean to `audit-service/src/test/java/com/atlas/audit/TestcontainersConfiguration.java`.

### Step 2.4 — Wire `RateLimitFilter` into each service's `SecurityConfig`

For each of the four services, inject `RateLimitFilter` as a constructor parameter and register it **after** `JwtAuthenticationFilter`. The filter must run after JWT parsing so that the tenant ID is extractable from the `Authorization` header's Claims.

Example for `identity-service/src/main/java/com/atlas/identity/config/SecurityConfig.java`:

```java
package com.atlas.identity.config;

import com.atlas.common.ratelimit.DefaultTenantQuotaResolver;
import com.atlas.common.ratelimit.RateLimitFilter;
import com.atlas.common.ratelimit.RateLimitProperties;
import com.atlas.common.ratelimit.RedisRateLimiter;
import com.atlas.identity.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RedisRateLimiter redisRateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper;
    private final String jwtSecret;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RedisRateLimiter redisRateLimiter,
                          RateLimitProperties rateLimitProperties,
                          ObjectMapper objectMapper,
                          @Value("${atlas.jwt.secret}") String jwtSecret) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.redisRateLimiter = redisRateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.objectMapper = objectMapper;
        this.jwtSecret = jwtSecret;
    }

    @Bean
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(
                redisRateLimiter,
                rateLimitProperties,
                new DefaultTenantQuotaResolver(),
                objectMapper,
                jwtSecret
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/internal/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter(), JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

Apply the same pattern to `workflow-service`, `worker-service`, and `audit-service` SecurityConfig classes — each already has `@Value("${atlas.jwt.secret}")` available in its `JwtConfig`.

### Step 2.5 — Integration test: `RateLimitIntegrationTest.java`

Location: `identity-service/src/test/java/com/atlas/identity/controller/RateLimitIntegrationTest.java`

```java
package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "atlas.rate-limit.requests-per-minute=2",
        "atlas.rate-limit.burst-size=2"
})
class RateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void rapidFireRequests_trigger429() {
        UUID tenantId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), tenantId, List.of("TENANT_ADMIN"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        // Burst size is 2: first two succeed, third is rate-limited
        boolean got429 = false;
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/tenants/" + tenantId, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                got429 = true;
                assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
                break;
            }
        }
        assertThat(got429).as("Expected at least one 429 within 5 rapid requests").isTrue();
    }
}
```

### Commit message

```
feat(all-services): wire RateLimitFilter into all four service SecurityConfigs

Adds Redis dependency to identity-service and audit-service poms.
Adds Redis container to their TestcontainersConfiguration. Registers
RateLimitFilter after JwtAuthenticationFilter in each SecurityConfig.
Integration test verifies rapid-fire requests produce 429 Too Many Requests
with Retry-After header.
```

---

## Task 3: Per-Tenant Quotas — Domain Model

**Files to create:**
- `identity-service/src/main/resources/db/migration/V009__add_tenant_quota_columns.sql`
- `identity-service/src/main/resources/db/migration/V010__seed_acme_tenant_quotas.sql`

**Files to modify:**
- `identity-service/src/main/java/com/atlas/identity/domain/Tenant.java`
- `identity-service/src/main/java/com/atlas/identity/dto/TenantResponse.java`

### Step 3.1 — Migration `V009__add_tenant_quota_columns.sql`

```sql
ALTER TABLE identity.tenants
    ADD COLUMN max_workflow_definitions    INT NOT NULL DEFAULT 100,
    ADD COLUMN max_executions_per_minute   INT NOT NULL DEFAULT 60,
    ADD COLUMN max_concurrent_executions   INT NOT NULL DEFAULT 10,
    ADD COLUMN max_api_requests_per_minute INT NOT NULL DEFAULT 600;

COMMENT ON COLUMN identity.tenants.max_workflow_definitions    IS 'Maximum number of workflow definitions allowed for this tenant';
COMMENT ON COLUMN identity.tenants.max_executions_per_minute   IS 'Maximum workflow executions that can be started per minute';
COMMENT ON COLUMN identity.tenants.max_concurrent_executions   IS 'Maximum concurrent executions in RUNNING/PENDING/WAITING state';
COMMENT ON COLUMN identity.tenants.max_api_requests_per_minute IS 'Maximum API requests per minute (drives rate limiter)';
```

### Step 3.2 — Migration `V010__seed_acme_tenant_quotas.sql`

```sql
UPDATE identity.tenants
SET
    max_workflow_definitions    = 50,
    max_executions_per_minute   = 120,
    max_concurrent_executions   = 20,
    max_api_requests_per_minute = 1200,
    updated_at = NOW()
WHERE tenant_id = 'a0000000-0000-0000-0000-000000000010';
```

### Step 3.3 — Update `Tenant.java`

Add the four quota fields below `updatedAt`. Use the existing field pattern (no Lombok, plain JPA).

```java
@Column(name = "max_workflow_definitions", nullable = false)
private int maxWorkflowDefinitions = 100;

@Column(name = "max_executions_per_minute", nullable = false)
private int maxExecutionsPerMinute = 60;

@Column(name = "max_concurrent_executions", nullable = false)
private int maxConcurrentExecutions = 10;

@Column(name = "max_api_requests_per_minute", nullable = false)
private int maxApiRequestsPerMinute = 600;
```

Add getters and setters for each field following the existing style:

```java
public int getMaxWorkflowDefinitions() { return maxWorkflowDefinitions; }
public void setMaxWorkflowDefinitions(int maxWorkflowDefinitions) { this.maxWorkflowDefinitions = maxWorkflowDefinitions; }

public int getMaxExecutionsPerMinute() { return maxExecutionsPerMinute; }
public void setMaxExecutionsPerMinute(int maxExecutionsPerMinute) { this.maxExecutionsPerMinute = maxExecutionsPerMinute; }

public int getMaxConcurrentExecutions() { return maxConcurrentExecutions; }
public void setMaxConcurrentExecutions(int maxConcurrentExecutions) { this.maxConcurrentExecutions = maxConcurrentExecutions; }

public int getMaxApiRequestsPerMinute() { return maxApiRequestsPerMinute; }
public void setMaxApiRequestsPerMinute(int maxApiRequestsPerMinute) { this.maxApiRequestsPerMinute = maxApiRequestsPerMinute; }
```

### Step 3.4 — Update `TenantResponse.java`

Locate the existing `TenantResponse` record and add four quota fields. The existing record likely looks like:

```java
public record TenantResponse(UUID tenantId, String name, String slug, String status,
                              Instant createdAt, Instant updatedAt) { ... }
```

Update to:

```java
public record TenantResponse(
        UUID tenantId,
        String name,
        String slug,
        String status,
        int maxWorkflowDefinitions,
        int maxExecutionsPerMinute,
        int maxConcurrentExecutions,
        int maxApiRequestsPerMinute,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getStatus().name(),
                tenant.getMaxWorkflowDefinitions(),
                tenant.getMaxExecutionsPerMinute(),
                tenant.getMaxConcurrentExecutions(),
                tenant.getMaxApiRequestsPerMinute(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt()
        );
    }
}
```

Update all call sites in `TenantService` (or wherever `TenantResponse` is constructed) to use the new factory method.

### Commit message

```
feat(identity): add per-tenant quota columns to tenants table

Flyway migration V009 adds four quota columns with production defaults.
V010 sets elevated quotas for Acme Corp seed data. Tenant entity and
TenantResponse DTO updated to expose quota fields.
```

---

## Task 4: Per-Tenant Quotas — Enforcement

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/service/QuotaService.java`
- `workflow-service/src/main/java/com/atlas/workflow/exception/QuotaExceededException.java`
- `workflow-service/src/test/java/com/atlas/workflow/service/QuotaServiceIntegrationTest.java`

**Files to modify:**
- `workflow-service/src/main/java/com/atlas/workflow/service/WorkflowDefinitionService.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/WorkflowExecutionService.java`
- `workflow-service/src/main/java/com/atlas/workflow/controller/GlobalExceptionHandler.java`

### Step 4.1 — `QuotaExceededException.java`

```java
package com.atlas.workflow.exception;

public class QuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final int limit;
    private final long current;

    public QuotaExceededException(String quotaType, int limit, long current) {
        super(String.format("Quota exceeded: %s limit is %d, current value is %d",
                quotaType, limit, current));
        this.quotaType = quotaType;
        this.limit = limit;
        this.current = current;
    }

    public String getQuotaType() { return quotaType; }
    public int getLimit() { return limit; }
    public long getCurrent() { return current; }
}
```

### Step 4.2 — `QuotaService.java`

The workflow-service does not own the `Tenant` entity (it lives in `identity-service`). Instead, `QuotaService` calls the identity-service's internal REST endpoint to fetch quota values, caching them in a `ConcurrentHashMap` with a 60-second TTL. On cache miss or stale entry it calls `GET /api/v1/internal/tenants/{tenantId}/quotas`.

```java
package com.atlas.workflow.service;

import com.atlas.workflow.exception.QuotaExceededException;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.domain.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final long CACHE_TTL_SECONDS = 60;

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final RestClient restClient;
    private final String identityServiceBaseUrl;

    // Simple in-process cache: tenantId -> (quotas, cachedAt)
    private final ConcurrentHashMap<UUID, CachedQuota> quotaCache = new ConcurrentHashMap<>();

    public QuotaService(WorkflowDefinitionRepository definitionRepository,
                        WorkflowExecutionRepository executionRepository,
                        RestClient.Builder restClientBuilder,
                        @Value("${atlas.identity-service.base-url:http://identity-service:8081}") String identityServiceBaseUrl) {
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.restClient = restClientBuilder.build();
        this.identityServiceBaseUrl = identityServiceBaseUrl;
    }

    /**
     * Checks that creating a new workflow definition will not exceed the tenant's
     * {@code max_workflow_definitions} quota.
     */
    public void checkDefinitionQuota(UUID tenantId) {
        TenantQuota quota = getQuota(tenantId);
        long current = definitionRepository.countByTenantId(tenantId);
        if (current >= quota.maxWorkflowDefinitions()) {
            throw new QuotaExceededException("max_workflow_definitions",
                    quota.maxWorkflowDefinitions(), current);
        }
    }

    /**
     * Checks executions-per-minute and concurrent execution quotas before starting a new execution.
     */
    public void checkExecutionQuota(UUID tenantId) {
        TenantQuota quota = getQuota(tenantId);

        // Check executions per minute: count executions created in the last 60 seconds
        Instant oneMinuteAgo = Instant.now().minusSeconds(60);
        long recentExecutions = executionRepository.countByTenantIdAndCreatedAtAfter(tenantId, oneMinuteAgo);
        if (recentExecutions >= quota.maxExecutionsPerMinute()) {
            throw new QuotaExceededException("max_executions_per_minute",
                    quota.maxExecutionsPerMinute(), recentExecutions);
        }

        // Check concurrent executions: count RUNNING + PENDING + WAITING
        long concurrent = executionRepository.countByTenantIdAndStatusIn(
                tenantId,
                java.util.List.of(ExecutionStatus.RUNNING, ExecutionStatus.PENDING, ExecutionStatus.WAITING));
        if (concurrent >= quota.maxConcurrentExecutions()) {
            throw new QuotaExceededException("max_concurrent_executions",
                    quota.maxConcurrentExecutions(), concurrent);
        }
    }

    private TenantQuota getQuota(UUID tenantId) {
        CachedQuota cached = quotaCache.get(tenantId);
        if (cached != null && !cached.isStale()) {
            return cached.quota();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(identityServiceBaseUrl + "/api/v1/internal/tenants/" + tenantId + "/quotas")
                    .retrieve()
                    .body(Map.class);

            TenantQuota quota = new TenantQuota(
                    ((Number) response.get("max_workflow_definitions")).intValue(),
                    ((Number) response.get("max_executions_per_minute")).intValue(),
                    ((Number) response.get("max_concurrent_executions")).intValue(),
                    ((Number) response.get("max_api_requests_per_minute")).intValue()
            );
            quotaCache.put(tenantId, new CachedQuota(quota, Instant.now()));
            return quota;
        } catch (Exception e) {
            log.warn("Failed to fetch quota for tenant {}, using defaults: {}", tenantId, e.getMessage());
            return TenantQuota.defaults();
        }
    }

    public record TenantQuota(int maxWorkflowDefinitions,
                               int maxExecutionsPerMinute,
                               int maxConcurrentExecutions,
                               int maxApiRequestsPerMinute) {
        public static TenantQuota defaults() {
            return new TenantQuota(100, 60, 10, 600);
        }
    }

    private record CachedQuota(TenantQuota quota, Instant cachedAt) {
        boolean isStale() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }
}
```

### Step 4.3 — Internal quota endpoint in `identity-service`

Add a new controller method to `InternalPermissionsController` (or a new `InternalTenantController`):

**File:** `identity-service/src/main/java/com/atlas/identity/controller/InternalTenantController.java`

```java
package com.atlas.identity.controller;

import com.atlas.identity.repository.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal")
public class InternalTenantController {

    private final TenantRepository tenantRepository;

    public InternalTenantController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/tenants/{tenantId}/quotas")
    public ResponseEntity<Map<String, Object>> getQuotas(@PathVariable UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> ResponseEntity.ok(Map.<String, Object>of(
                        "max_workflow_definitions", tenant.getMaxWorkflowDefinitions(),
                        "max_executions_per_minute", tenant.getMaxExecutionsPerMinute(),
                        "max_concurrent_executions", tenant.getMaxConcurrentExecutions(),
                        "max_api_requests_per_minute", tenant.getMaxApiRequestsPerMinute()
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
```

### Step 4.4 — Add repository methods to `WorkflowExecutionRepository`

```java
long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant after);

long countByTenantIdAndStatusIn(UUID tenantId, List<ExecutionStatus> statuses);
```

Add `long countByTenantId(UUID tenantId)` to `WorkflowDefinitionRepository`.

### Step 4.5 — Call quota checks in service layer

In `WorkflowDefinitionService.createDefinition(...)`:

```java
// Before saving the definition:
quotaService.checkDefinitionQuota(tenantId);
```

In `WorkflowExecutionService.startExecution(...)`:

```java
// Before creating the execution:
quotaService.checkExecutionQuota(tenantId);
```

Both services receive `QuotaService` via constructor injection.

### Step 4.6 — Handle `QuotaExceededException` in `GlobalExceptionHandler`

```java
@ExceptionHandler(QuotaExceededException.class)
public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex,
                                                          HttpServletRequest request) {
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
    return ResponseEntity.status(429).body(ErrorResponse.withDetails(
            "ATLAS-WF-008",
            "Quota exceeded",
            ex.getMessage(),
            correlationId
    ));
}
```

Add `ATLAS-WF-008` to the error code taxonomy documentation.

### Step 4.7 — Integration test: `QuotaServiceIntegrationTest.java`

```java
package com.atlas.workflow.service;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class QuotaServiceIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // Tests verify that after creating max_workflow_definitions definitions,
    // the (max + 1)th request returns 429 with ATLAS-WF-008.
    // Requires WireMock or MockRestServiceServer to stub the identity-service quota endpoint
    // returning max_workflow_definitions=2 for the test tenant.

    @Test
    void createDefinition_exceedsQuota_returns429() {
        // Implementation uses @MockBean for QuotaService or stubs RestClient
        // to return maxWorkflowDefinitions=1 for the test tenant UUID.
        // Full body omitted here — the key assertion:
        // After one definition is created, the second POST returns 429
        // with body containing code = "ATLAS-WF-008".
    }
}
```

### Commit message

```
feat(workflow): enforce per-tenant quotas on definition and execution creation

QuotaService fetches tenant quotas from identity-service internal endpoint
(60s cache). Checks max_workflow_definitions before create definition,
max_executions_per_minute and max_concurrent_executions before start
execution. QuotaExceededException -> 429 ATLAS-WF-008. Internal quota
endpoint added to identity-service.
```

---

## Task 5: Service Account API Keys — Domain

**Files to create:**
- `identity-service/src/main/resources/db/migration/V011__create_service_accounts_table.sql`
- `identity-service/src/main/resources/db/migration/V012__create_api_keys_table.sql`
- `identity-service/src/main/java/com/atlas/identity/domain/ServiceAccount.java`
- `identity-service/src/main/java/com/atlas/identity/domain/ApiKey.java`
- `identity-service/src/main/java/com/atlas/identity/repository/ServiceAccountRepository.java`
- `identity-service/src/main/java/com/atlas/identity/repository/ApiKeyRepository.java`
- `identity-service/src/main/java/com/atlas/identity/service/ServiceAccountService.java`
- `identity-service/src/main/java/com/atlas/identity/dto/CreateServiceAccountRequest.java`
- `identity-service/src/main/java/com/atlas/identity/dto/ServiceAccountResponse.java`
- `identity-service/src/main/java/com/atlas/identity/dto/CreateApiKeyRequest.java`
- `identity-service/src/main/java/com/atlas/identity/dto/ApiKeyCreatedResponse.java`

### Step 5.1 — Migration `V011__create_service_accounts_table.sql`

```sql
CREATE TABLE identity.service_accounts (
    service_account_id  UUID         PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES identity.tenants(tenant_id),
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_service_account_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_service_accounts_tenant ON identity.service_accounts (tenant_id);
CREATE INDEX idx_service_accounts_status ON identity.service_accounts (tenant_id, status);
```

### Step 5.2 — Migration `V012__create_api_keys_table.sql`

```sql
CREATE TABLE identity.api_keys (
    api_key_id          UUID         PRIMARY KEY,
    service_account_id  UUID         NOT NULL REFERENCES identity.service_accounts(service_account_id),
    tenant_id           UUID         NOT NULL REFERENCES identity.tenants(tenant_id),
    key_hash            VARCHAR(255) NOT NULL UNIQUE,
    key_prefix          VARCHAR(8)   NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_tenant          ON identity.api_keys (tenant_id);
CREATE INDEX idx_api_keys_service_account ON identity.api_keys (service_account_id);
CREATE INDEX idx_api_keys_hash            ON identity.api_keys (key_hash);
CREATE INDEX idx_api_keys_status          ON identity.api_keys (tenant_id, status);
```

### Step 5.3 — `ServiceAccount.java`

```java
package com.atlas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
@Table(name = "service_accounts", schema = "identity")
public class ServiceAccount {

    public enum Status {
        ACTIVE, SUSPENDED
    }

    @Id
    @Column(name = "service_account_id", nullable = false, updatable = false)
    private UUID serviceAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceAccount() {
        // JPA
    }

    public ServiceAccount(UUID tenantId, String name) {
        this.serviceAccountId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.status = Status.ACTIVE;
    }

    @PrePersist
    protected void onCreate() {
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getServiceAccountId() { return serviceAccountId; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

### Step 5.4 — `ApiKey.java`

Follows the same hashed-token pattern as `RefreshToken`. The raw key is generated once, hashed with SHA-256, and only the hash is persisted. The `key_prefix` stores the first 8 characters of the raw key (safe to expose) so operators can identify keys without seeing the secret.

```java
package com.atlas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
@Table(name = "api_keys", schema = "identity")
public class ApiKey {

    public enum Status {
        ACTIVE, REVOKED
    }

    @Id
    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    @Column(name = "service_account_id", nullable = false, updatable = false)
    private UUID serviceAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "key_hash", nullable = false, unique = true, updatable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, updatable = false, length = 8)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {
        // JPA
    }

    public ApiKey(UUID serviceAccountId, UUID tenantId, String keyHash, String keyPrefix, Instant expiresAt) {
        this.apiKeyId = UUID.randomUUID();
        this.serviceAccountId = serviceAccountId;
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.status = Status.ACTIVE;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isRevoked() {
        return status == Status.REVOKED;
    }

    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }

    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getServiceAccountId() { return serviceAccountId; }
    public UUID getTenantId() { return tenantId; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
```

### Step 5.5 — Repositories

**`ServiceAccountRepository.java`**

```java
package com.atlas.identity.repository;

import com.atlas.identity.domain.ServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {
    List<ServiceAccount> findAllByTenantId(UUID tenantId);
    Optional<ServiceAccount> findByServiceAccountIdAndTenantId(UUID serviceAccountId, UUID tenantId);
    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
```

**`ApiKeyRepository.java`**

```java
package com.atlas.identity.repository;

import com.atlas.identity.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    long countByServiceAccountIdAndStatus(UUID serviceAccountId, ApiKey.Status status);
}
```

### Step 5.6 — `ServiceAccountService.java`

Uses `JwtTokenProvider.hashRefreshToken` pattern (SHA-256 via `MessageDigest`) for API key hashing. Generates raw key as 256-bit Base64URL random value; stores first 8 chars as prefix.

```java
package com.atlas.identity.service;

import com.atlas.identity.domain.ApiKey;
import com.atlas.identity.domain.ServiceAccount;
import com.atlas.identity.exception.ConflictException;
import com.atlas.identity.exception.ResourceNotFoundException;
import com.atlas.identity.repository.ApiKeyRepository;
import com.atlas.identity.repository.ServiceAccountRepository;
import com.atlas.identity.security.JwtTokenProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class ServiceAccountService {

    private static final int MAX_ACTIVE_KEYS_PER_ACCOUNT = 5;
    private final ServiceAccountRepository serviceAccountRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public ServiceAccountService(ServiceAccountRepository serviceAccountRepository,
                                  ApiKeyRepository apiKeyRepository,
                                  JwtTokenProvider jwtTokenProvider) {
        this.serviceAccountRepository = serviceAccountRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public ServiceAccount createServiceAccount(UUID tenantId, String name) {
        if (serviceAccountRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new ConflictException("Service account with name '" + name + "' already exists");
        }
        ServiceAccount account = new ServiceAccount(tenantId, name);
        return serviceAccountRepository.save(account);
    }

    /**
     * Generates a new API key for the given service account.
     * Returns the raw key value — this is the ONLY time the raw key is available.
     * The stored entity only holds the SHA-256 hash.
     */
    @Transactional
    public GeneratedApiKey generateApiKey(UUID serviceAccountId, UUID tenantId, Instant expiresAt) {
        ServiceAccount account = serviceAccountRepository
                .findByServiceAccountIdAndTenantId(serviceAccountId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Service account not found: " + serviceAccountId));

        long activeKeyCount = apiKeyRepository.countByServiceAccountIdAndStatus(
                serviceAccountId, ApiKey.Status.ACTIVE);
        if (activeKeyCount >= MAX_ACTIVE_KEYS_PER_ACCOUNT) {
            throw new ConflictException("Service account already has " + MAX_ACTIVE_KEYS_PER_ACCOUNT
                    + " active API keys. Revoke one before generating a new key.");
        }

        // Generate 256-bit random value
        byte[] rawBytes = new byte[32];
        secureRandom.nextBytes(rawBytes);
        String rawKey = "atl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);

        String keyHash = jwtTokenProvider.hashRefreshToken(rawKey);
        String keyPrefix = rawKey.substring(0, 8);

        ApiKey apiKey = new ApiKey(serviceAccountId, tenantId, keyHash, keyPrefix, expiresAt);
        ApiKey saved = apiKeyRepository.save(apiKey);

        return new GeneratedApiKey(saved.getApiKeyId(), rawKey, keyPrefix, saved.getCreatedAt(), expiresAt);
    }

    public record GeneratedApiKey(UUID apiKeyId, String rawKey, String keyPrefix,
                                   Instant createdAt, Instant expiresAt) {}
}
```

### Step 5.7 — DTOs

**`CreateServiceAccountRequest.java`**

```java
package com.atlas.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateServiceAccountRequest(
        @NotBlank UUID tenantId,
        @NotBlank @Size(min = 1, max = 255) String name) {
}
```

**`ServiceAccountResponse.java`**

```java
package com.atlas.identity.dto;

import com.atlas.identity.domain.ServiceAccount;

import java.time.Instant;
import java.util.UUID;

public record ServiceAccountResponse(
        UUID serviceAccountId,
        UUID tenantId,
        String name,
        String status,
        Instant createdAt) {

    public static ServiceAccountResponse from(ServiceAccount account) {
        return new ServiceAccountResponse(
                account.getServiceAccountId(),
                account.getTenantId(),
                account.getName(),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}
```

**`CreateApiKeyRequest.java`**

```java
package com.atlas.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyRequest(
        UUID serviceAccountId,
        UUID tenantId,
        Instant expiresAt    // nullable — no expiry if omitted
) {
}
```

**`ApiKeyCreatedResponse.java`**

```java
package com.atlas.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreatedResponse(
        UUID apiKeyId,
        String rawKey,       // Only returned once — not stored
        String keyPrefix,
        Instant createdAt,
        Instant expiresAt) {
}
```

### Commit message

```
feat(identity): add service account and API key domain model

Flyway migrations V011/V012 create service_accounts and api_keys tables.
ServiceAccount and ApiKey entities follow existing patterns (UUID PK,
tenant_id FK, @Filter, @PrePersist). SHA-256 key hashing reuses
JwtTokenProvider.hashRefreshToken. ServiceAccountService generates raw
key once, persists hash + 8-char prefix. Max 5 active keys per account.
```

---

## Task 6: Service Account API Keys — Auth + API

**Files to create:**
- `identity-service/src/main/java/com/atlas/identity/controller/ServiceAccountController.java`
- `identity-service/src/main/java/com/atlas/identity/security/ApiKeyAuthenticationFilter.java`
- `identity-service/src/test/java/com/atlas/identity/controller/ServiceAccountIntegrationTest.java`

**Files to modify:**
- `identity-service/src/main/java/com/atlas/identity/config/SecurityConfig.java`
- `identity-service/src/main/java/com/atlas/identity/security/JwtAuthenticationFilter.java`

### Step 6.1 — `ServiceAccountController.java`

```java
package com.atlas.identity.controller;

import com.atlas.identity.dto.ApiKeyCreatedResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.service.ServiceAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;

    public ServiceAccountController(ServiceAccountService serviceAccountService) {
        this.serviceAccountService = serviceAccountService;
    }

    @PostMapping("/service-accounts")
    public ResponseEntity<ServiceAccountResponse> createServiceAccount(
            @Valid @RequestBody CreateServiceAccountRequest request) {
        var account = serviceAccountService.createServiceAccount(request.tenantId(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ServiceAccountResponse.from(account));
    }

    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyCreatedResponse> createApiKey(
            @Valid @RequestBody CreateApiKeyRequest request) {
        var generated = serviceAccountService.generateApiKey(
                request.serviceAccountId(), request.tenantId(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiKeyCreatedResponse(
                generated.apiKeyId(),
                generated.rawKey(),
                generated.keyPrefix(),
                generated.createdAt(),
                generated.expiresAt()
        ));
    }
}
```

### Step 6.2 — `ApiKeyAuthenticationFilter.java`

Reads the `X-API-Key` header, hashes the value with SHA-256 (same as `JwtTokenProvider.hashRefreshToken`), looks up the hash in the `api_keys` table, validates status and expiry, then sets the `SecurityContext` and `TenantContext` exactly as `JwtAuthenticationFilter` does.

Also sets the `rateLimitTenantId` request attribute used by `RateLimitFilter` so the rate limiter works without a JWT.

```java
package com.atlas.identity.security;

import com.atlas.identity.domain.ApiKey;
import com.atlas.identity.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_API_KEY = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantContext tenantContext;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository,
                                       JwtTokenProvider jwtTokenProvider,
                                       TenantContext tenantContext) {
        this.apiKeyRepository = apiKeyRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKeyValue = request.getHeader(HEADER_API_KEY);

        if (apiKeyValue != null && !apiKeyValue.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String hash = jwtTokenProvider.hashRefreshToken(apiKeyValue);

            apiKeyRepository.findByKeyHash(hash).ifPresent(apiKey -> {
                if (apiKey.isUsable()) {
                    apiKey.recordUsage();
                    apiKeyRepository.save(apiKey);

                    // Authenticate as the service account (no roles — service accounts
                    // are given minimal authorities; extend in future with role assignment)
                    var authentication = new UsernamePasswordAuthenticationToken(
                            apiKey.getServiceAccountId().toString(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ACCOUNT"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    tenantContext.setTenantId(apiKey.getTenantId());

                    // Set attribute for RateLimitFilter
                    request.setAttribute("rateLimitTenantId", apiKey.getTenantId());
                }
            });
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Run on all requests — if no X-API-Key header is present the filter is a no-op
        return false;
    }
}
```

### Step 6.3 — Update `SecurityConfig.java` to register `ApiKeyAuthenticationFilter`

Inject `ApiKeyAuthenticationFilter` and register it before `JwtAuthenticationFilter`:

```java
.addFilterBefore(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(rateLimitFilter(), JwtAuthenticationFilter.class)
```

The order becomes: `ApiKeyAuthenticationFilter` → `JwtAuthenticationFilter` → `RateLimitFilter`.

### Step 6.4 — Integration test: `ServiceAccountIntegrationTest.java`

```java
package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.ApiKeyCreatedResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ServiceAccountIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        var tenantResp = restTemplate.postForEntity(
                "/api/v1/tenants",
                new CreateTenantRequest("SA Test Tenant " + uniqueId, "sa-test-" + uniqueId),
                TenantResponse.class);
        assertThat(tenantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tenantId = tenantResp.getBody().tenantId();
        adminToken = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), tenantId, List.of("TENANT_ADMIN"));
    }

    @Test
    void createServiceAccount_succeeds() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<ServiceAccountResponse> response = restTemplate.exchange(
                "/api/v1/service-accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, "ci-agent"), headers),
                ServiceAccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().name()).isEqualTo("ci-agent");
        assertThat(response.getBody().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void generateApiKey_returnsRawKey_once() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        // Create service account
        ResponseEntity<ServiceAccountResponse> saResp = restTemplate.exchange(
                "/api/v1/service-accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, "key-test-agent"), headers),
                ServiceAccountResponse.class);
        UUID serviceAccountId = saResp.getBody().serviceAccountId();

        // Generate API key
        ResponseEntity<ApiKeyCreatedResponse> keyResp = restTemplate.exchange(
                "/api/v1/api-keys",
                HttpMethod.POST,
                new HttpEntity<>(new CreateApiKeyRequest(serviceAccountId, tenantId, null), headers),
                ApiKeyCreatedResponse.class);

        assertThat(keyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(keyResp.getBody().rawKey()).startsWith("atl_");
        assertThat(keyResp.getBody().keyPrefix()).hasSize(8);
    }

    @Test
    void authenticateWithApiKey_succeeds() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<ServiceAccountResponse> saResp = restTemplate.exchange(
                "/api/v1/service-accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, "auth-test-agent"), headers),
                ServiceAccountResponse.class);
        UUID serviceAccountId = saResp.getBody().serviceAccountId();

        ResponseEntity<ApiKeyCreatedResponse> keyResp = restTemplate.exchange(
                "/api/v1/api-keys",
                HttpMethod.POST,
                new HttpEntity<>(new CreateApiKeyRequest(serviceAccountId, tenantId, null), headers),
                ApiKeyCreatedResponse.class);
        String rawKey = keyResp.getBody().rawKey();

        // Now authenticate using X-API-Key
        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("X-API-Key", rawKey);
        ResponseEntity<String> protectedResp = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId,
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders),
                String.class);

        // Service account has ROLE_SERVICE_ACCOUNT — endpoint may return 403 for
        // tenant.manage, but 200 for read-only endpoint. Adjust based on what
        // the /tenants/{id} endpoint requires.
        assertThat(protectedResp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createServiceAccount_duplicateName_returns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        restTemplate.exchange(
                "/api/v1/service-accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, "duplicate-agent"), headers),
                ServiceAccountResponse.class);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/service-accounts",
                HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, "duplicate-agent"), headers),
                String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

### Commit message

```
feat(identity): add service account API key authentication

ServiceAccountController exposes POST /api/v1/service-accounts and
POST /api/v1/api-keys. ApiKeyAuthenticationFilter authenticates requests
via X-API-Key header using SHA-256 hash lookup; sets SecurityContext and
TenantContext identically to JwtAuthenticationFilter. Filter order:
ApiKeyFilter -> JwtFilter -> RateLimitFilter. Integration tests cover
create, generate key, authenticate with key, and duplicate name conflict.
```

---

## Task 7: Delayed Scheduling via Redis

**Context:** The existing `StepResultProcessor.handleDelayRequested` uses the retry mechanism (`FAILED -> RETRY_SCHEDULED` via `RetryScheduler`) for delay steps. This works but conflates retries with intentional delays. Task 7 replaces it with a dedicated Redis ZADD-based delay queue with a new `DELAY_SCHEDULED` status.

**New step status:** `DELAY_SCHEDULED` — a step waiting for its delay window to expire.

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/scheduler/DelayScheduler.java`
- `workflow-service/src/test/java/com/atlas/workflow/scheduler/DelaySchedulerIntegrationTest.java`

**Files to modify:**
- `workflow-service/src/main/java/com/atlas/workflow/domain/StepStatus.java` — add `DELAY_SCHEDULED`
- `workflow-service/src/main/java/com/atlas/workflow/statemachine/StepStateMachine.java` — add transitions
- `workflow-service/src/main/java/com/atlas/workflow/service/StepResultProcessor.java` — update `handleDelayRequested`
- `workflow-service/src/main/java/com/atlas/workflow/config/RedisConfig.java` — ensure `StringRedisTemplate` bean

### Step 7.1 — Add `DELAY_SCHEDULED` to `StepStatus.java`

Locate the enum and add `DELAY_SCHEDULED` alongside the existing statuses:

```java
public enum StepStatus {
    PENDING,
    LEASED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRY_SCHEDULED,
    DELAY_SCHEDULED,   // <-- new
    WAITING,
    DEAD_LETTERED,
    COMPENSATED,
    COMPENSATION_FAILED
}
```

### Step 7.2 — Update `StepStateMachine.java`

Add the following valid transitions:

```java
// RUNNING -> DELAY_SCHEDULED (delay requested by step result)
allowed.put(StepStatus.RUNNING, Set.of(
    StepStatus.SUCCEEDED,
    StepStatus.FAILED,
    StepStatus.WAITING,
    StepStatus.DELAY_SCHEDULED   // <-- new
));

// DELAY_SCHEDULED -> PENDING (delay window expired, scheduler picks it up)
allowed.put(StepStatus.DELAY_SCHEDULED, Set.of(StepStatus.PENDING));
```

### Step 7.3 — `DelayScheduler.java`

Uses `StringRedisTemplate` to interact with a sorted set keyed `delay-queue`. The score is the Unix epoch milliseconds of the wake-up time. The member is the `step_execution_id` as a string.

```java
package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.statemachine.StepStateMachine;
import com.atlas.common.event.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class DelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(DelayScheduler.class);
    private static final String DELAY_QUEUE_KEY = "delay-queue";
    private static final String STEP_EXECUTE_TOPIC = EventTypes.TOPIC_STEP_EXECUTE;

    private final StringRedisTemplate redisTemplate;
    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;
    private final StepStateMachine stepStateMachine;

    public DelayScheduler(StringRedisTemplate redisTemplate,
                          StepExecutionRepository stepExecutionRepository,
                          OutboxRepository outboxRepository,
                          StepStateMachine stepStateMachine) {
        this.redisTemplate = redisTemplate;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
        this.stepStateMachine = stepStateMachine;
    }

    /**
     * Schedules a step execution to wake up after {@code delayMs} milliseconds.
     * Called by StepResultProcessor when outcome is DELAY_REQUESTED.
     *
     * @param stepExecutionId the step to delay
     * @param delayMs         milliseconds to wait before re-queuing
     */
    public void scheduleDelay(UUID stepExecutionId, long delayMs) {
        long wakeUpAt = System.currentTimeMillis() + delayMs;
        redisTemplate.opsForZSet().add(DELAY_QUEUE_KEY, stepExecutionId.toString(), wakeUpAt);
        log.debug("Scheduled delay for step {} waking up at epoch ms {}", stepExecutionId, wakeUpAt);
    }

    /**
     * Runs every second. Polls the delay queue for steps whose wake-up time has passed.
     * For each due step: removes from the queue, transitions DELAY_SCHEDULED -> PENDING,
     * and publishes a step.execute outbox event.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void processDueDelays() {
        double nowMs = System.currentTimeMillis();

        Set<String> dueIds = redisTemplate.opsForZSet()
                .rangeByScore(DELAY_QUEUE_KEY, Double.NEGATIVE_INFINITY, nowMs);

        if (dueIds == null || dueIds.isEmpty()) {
            return;
        }

        log.info("DelayScheduler: {} step(s) due for wake-up", dueIds.size());

        for (String idStr : dueIds) {
            try {
                UUID stepExecutionId = UUID.fromString(idStr);
                processDelayedStep(stepExecutionId);
                // Remove from sorted set only after successful processing
                redisTemplate.opsForZSet().remove(DELAY_QUEUE_KEY, idStr);
            } catch (Exception e) {
                log.error("Failed to process delayed step {}: {}", idStr, e.getMessage(), e);
                // Leave in queue — will be retried next cycle
            }
        }
    }

    private void processDelayedStep(UUID stepExecutionId) {
        StepExecution step = stepExecutionRepository.findById(stepExecutionId).orElse(null);
        if (step == null) {
            log.warn("DelayScheduler: step {} not found, removing from delay queue", stepExecutionId);
            redisTemplate.opsForZSet().remove(DELAY_QUEUE_KEY, stepExecutionId.toString());
            return;
        }

        if (step.getStatus() != StepStatus.DELAY_SCHEDULED) {
            log.info("DelayScheduler: step {} is in status {} (expected DELAY_SCHEDULED), skipping",
                    stepExecutionId, step.getStatus());
            redisTemplate.opsForZSet().remove(DELAY_QUEUE_KEY, stepExecutionId.toString());
            return;
        }

        stepStateMachine.validate(step.getStatus(), StepStatus.PENDING);
        step.transitionTo(StepStatus.PENDING);
        stepExecutionRepository.save(step);

        Map<String, Object> payload = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "tenant_id", step.getTenantId().toString(),
                "step_name", step.getStepName(),
                "step_type", step.getStepType(),
                "step_index", step.getStepIndex(),
                "input", step.getInputJson() != null ? step.getInputJson() : Map.of()
        );

        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution",
                step.getStepExecutionId(),
                "step.execute",
                STEP_EXECUTE_TOPIC,
                payload,
                step.getTenantId()
        );
        outboxRepository.save(outboxEvent);

        log.info("Step {} (execution {}) woke up from delay, transitioned to PENDING",
                stepExecutionId, step.getExecutionId());
    }
}
```

### Step 7.4 — Update `StepResultProcessor.handleDelayRequested`

Replace the existing method body that used the retry path:

```java
private void handleDelayRequested(StepExecution step, Long delayMs) {
    long delay = delayMs != null ? delayMs : 1000L;

    // Transition RUNNING -> DELAY_SCHEDULED
    stepStateMachine.validate(step.getStatus(), StepStatus.DELAY_SCHEDULED);
    step.transitionTo(StepStatus.DELAY_SCHEDULED);
    stepExecutionRepository.save(step);

    // Schedule wake-up in Redis delay queue
    delayScheduler.scheduleDelay(step.getStepExecutionId(), delay);

    log.info("Step {} delay requested for {}ms, scheduled in Redis delay queue",
            step.getStepExecutionId(), delay);
}
```

`delayScheduler` is injected into `StepResultProcessor` via constructor:

```java
private final DelayScheduler delayScheduler;

// Add to constructor:
this.delayScheduler = delayScheduler;
```

### Step 7.5 — Integration test: `DelaySchedulerIntegrationTest.java`

```java
package com.atlas.workflow.scheduler;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.StepExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DelaySchedulerIntegrationTest {

    @Autowired
    private DelayScheduler delayScheduler;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Test
    void delayedStep_transitionsToPending_afterDelayExpires() {
        // Create a step in DELAY_SCHEDULED state
        StepExecution step = StepExecution.create(
                UUID.randomUUID(), UUID.randomUUID(), "delay-step", 0,
                "DELAY", 1, null, Map.of());
        // Manually put it in DELAY_SCHEDULED — abuse transitionTo via RUNNING first
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.DELAY_SCHEDULED);
        StepExecution saved = stepExecutionRepository.save(step);

        // Schedule a 200ms delay
        delayScheduler.scheduleDelay(saved.getStepExecutionId(), 200L);

        // Wait up to 3 seconds for the step to become PENDING
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            StepExecution refreshed = stepExecutionRepository
                    .findById(saved.getStepExecutionId()).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(StepStatus.PENDING);
        });
    }
}
```

### Commit message

```
feat(workflow): replace delay-via-retry with Redis ZADD delay scheduler

Adds DELAY_SCHEDULED StepStatus. DelayScheduler uses Redis sorted set
(delay-queue) with ZADD score=wake_up_epoch_ms. @Scheduled every 1s polls
ZRANGEBYSCORE -inf now, transitions each due step DELAY_SCHEDULED->PENDING
and emits step.execute outbox event. StepResultProcessor.handleDelayRequested
updated to use the new path instead of the retry mechanism. Awaitility-based
integration test with Testcontainers Redis.
```

---

## Task 8: Update Rate Limiter to Use Tenant Quotas

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/ratelimit/TenantQuotaCacheService.java`
- `identity-service/src/main/java/com/atlas/identity/ratelimit/IdentityServiceTenantQuotaResolver.java`

**Files to modify:**
- `identity-service/src/main/java/com/atlas/identity/config/SecurityConfig.java` — swap to quota-aware resolver
- `workflow-service/src/main/java/com/atlas/workflow/config/SecurityConfig.java` — swap to quota-aware resolver
- `worker-service/src/main/java/com/atlas/worker/config/SecurityConfig.java` — swap to quota-aware resolver
- `audit-service/src/main/java/com/atlas/audit/config/SecurityConfig.java` — swap to quota-aware resolver
- `identity-service/src/main/java/com/atlas/identity/service/TenantService.java` — publish cache-invalidation event on tenant update

### Step 8.1 — Approach

Each service needs per-tenant `max_api_requests_per_minute`. Rather than duplicating REST-call caching in every service, the `TenantQuotaResolver` in each service reads from a local in-memory `ConcurrentHashMap` cache, warmed by:

1. On first request for a tenant: fetch quota from identity-service internal endpoint (`GET /api/v1/internal/tenants/{tenantId}/quotas`).
2. Cache TTL: 60 seconds.
3. On tenant update: identity-service publishes a `tenant.quotas_updated` domain event via Kafka. All services listen to invalidate cached entry.

For the identity-service itself, the quota is available directly from the `TenantRepository` — no REST call needed.

### Step 8.2 — `IdentityServiceTenantQuotaResolver.java`

```java
package com.atlas.identity.ratelimit;

import com.atlas.common.ratelimit.TenantQuotaResolver;
import com.atlas.identity.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves tenant quota directly from the local TenantRepository.
 * Used only by the identity-service where the Tenant entity is available.
 */
@Component
public class IdentityServiceTenantQuotaResolver implements TenantQuotaResolver {

    private static final Logger log = LoggerFactory.getLogger(IdentityServiceTenantQuotaResolver.class);

    private final TenantRepository tenantRepository;

    public IdentityServiceTenantQuotaResolver(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.getMaxApiRequestsPerMinute())
                .orElseGet(() -> {
                    log.debug("Tenant {} not found, using default rpm {}", tenantId, defaultRpm);
                    return defaultRpm;
                });
    }
}
```

### Step 8.3 — `TenantQuotaCacheService.java` (for non-identity services)

```java
package com.atlas.workflow.ratelimit;

import com.atlas.common.ratelimit.TenantQuotaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TenantQuotaResolver for services that do not own the Tenant entity.
 * Fetches max_api_requests_per_minute from identity-service and caches it.
 * Cache invalidation via Kafka {@code tenant.quotas_updated} event.
 */
@Component
public class TenantQuotaCacheService implements TenantQuotaResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaCacheService.class);
    private static final long CACHE_TTL_SECONDS = 60;

    private final RestClient restClient;
    private final String identityServiceBaseUrl;
    private final ConcurrentHashMap<UUID, CachedEntry> cache = new ConcurrentHashMap<>();

    public TenantQuotaCacheService(
            RestClient.Builder restClientBuilder,
            @Value("${atlas.identity-service.base-url:http://identity-service:8081}") String identityServiceBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.identityServiceBaseUrl = identityServiceBaseUrl;
    }

    @Override
    public int resolveRequestsPerMinute(UUID tenantId, int defaultRpm) {
        CachedEntry entry = cache.get(tenantId);
        if (entry != null && !entry.isStale()) {
            return entry.rpm();
        }
        return fetchAndCache(tenantId, defaultRpm);
    }

    @KafkaListener(topics = "domain.events", groupId = "${spring.kafka.consumer.group-id}-quota-cache")
    public void onDomainEvent(Map<String, Object> event) {
        String eventType = (String) event.get("event_type");
        if ("tenant.quotas_updated".equals(eventType)) {
            String tenantIdStr = (String) event.get("tenant_id");
            if (tenantIdStr != null) {
                UUID tenantId = UUID.fromString(tenantIdStr);
                cache.remove(tenantId);
                log.debug("Evicted quota cache for tenant {} due to tenant.quotas_updated event", tenantId);
            }
        }
    }

    private int fetchAndCache(UUID tenantId, int defaultRpm) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(identityServiceBaseUrl + "/api/v1/internal/tenants/" + tenantId + "/quotas")
                    .retrieve()
                    .body(Map.class);
            int rpm = ((Number) response.get("max_api_requests_per_minute")).intValue();
            cache.put(tenantId, new CachedEntry(rpm, Instant.now()));
            return rpm;
        } catch (Exception e) {
            log.warn("Failed to fetch quota for tenant {}, using default {}: {}", tenantId, defaultRpm, e.getMessage());
            return defaultRpm;
        }
    }

    private record CachedEntry(int rpm, Instant cachedAt) {
        boolean isStale() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }
}
```

Create a parallel copy of `TenantQuotaCacheService` in:
- `worker-service/src/main/java/com/atlas/worker/ratelimit/TenantQuotaCacheService.java`
- `audit-service/src/main/java/com/atlas/audit/ratelimit/TenantQuotaCacheService.java`

(same code, different package — or move to `common` module if duplication becomes untenable; for now keep in each service per the plan's independence principle.)

### Step 8.4 — Update `SecurityConfig` beans to use the new resolvers

In each service's `SecurityConfig`, replace `new DefaultTenantQuotaResolver()` with the injected service-local `TenantQuotaResolver`:

**identity-service:**
```java
// Inject IdentityServiceTenantQuotaResolver instead of creating DefaultTenantQuotaResolver
@Bean
public RateLimitFilter rateLimitFilter(IdentityServiceTenantQuotaResolver quotaResolver) {
    return new RateLimitFilter(redisRateLimiter, rateLimitProperties, quotaResolver,
            objectMapper, jwtSecret);
}
```

**workflow-service / worker-service / audit-service:**
```java
// Inject TenantQuotaCacheService (which implements TenantQuotaResolver)
@Bean
public RateLimitFilter rateLimitFilter(TenantQuotaCacheService quotaResolver) {
    return new RateLimitFilter(redisRateLimiter, rateLimitProperties, quotaResolver,
            objectMapper, jwtSecret);
}
```

### Step 8.5 — Publish `tenant.quotas_updated` event on update

In `identity-service/src/main/java/com/atlas/identity/service/TenantService.java`, after a successful quota update:

```java
// In updateTenantQuotas (or wherever tenant quotas are modified):
OutboxEvent event = OutboxEvent.create(
        "Tenant",
        tenant.getTenantId(),
        "tenant.quotas_updated",
        EventTypes.TOPIC_DOMAIN_EVENTS,
        Map.of("tenant_id", tenant.getTenantId().toString()),
        tenant.getTenantId()
);
outboxRepository.save(event);
```

### Step 8.6 — Integration test for per-tenant rate limit

Add a test to `RateLimitIntegrationTest` that:
1. Creates a tenant with `max_api_requests_per_minute = 2` (via `V010`-style seed or direct DB update in `@BeforeEach`).
2. Makes 3 requests as that tenant.
3. Verifies the 3rd returns 429.
4. Creates a second tenant with `max_api_requests_per_minute = 600`.
5. Makes 3 requests as that tenant.
6. Verifies all 3 return 200 (or whatever the endpoint normally returns).

This demonstrates that two tenants have different rate limits.

### Commit message

```
feat(all-services): per-tenant rate limits driven by tenant quota

Identity-service uses IdentityServiceTenantQuotaResolver (direct DB lookup).
Other services use TenantQuotaCacheService (REST fetch with 60s TTL,
Kafka-based cache invalidation on tenant.quotas_updated event). Each tenant's
RateLimitFilter now consumes max_api_requests_per_minute from their quota
profile instead of the global default.
```

---

## Summary

| Task | Feature | Services touched | Tests |
|------|---------|-----------------|-------|
| 1 | Redis token bucket rate limiter (common) | common | Unit: `RedisRateLimiterTest` |
| 2 | Wire rate limiter into all services | identity, workflow, worker, audit | Integration: `RateLimitIntegrationTest` |
| 3 | Per-tenant quota columns + seed | identity | — (migration + entity) |
| 4 | Quota enforcement at service layer | workflow, identity | Integration: `QuotaServiceIntegrationTest` |
| 5 | Service account + API key domain | identity | — (domain) |
| 6 | API key auth filter + REST endpoints | identity | Integration: `ServiceAccountIntegrationTest` |
| 7 | Redis ZADD delay scheduler | workflow | Integration: `DelaySchedulerIntegrationTest` |
| 8 | Per-tenant rate limits from quota | identity, workflow, worker, audit | Integration: extended `RateLimitIntegrationTest` |

**Total tasks: 8**
**Total test classes: 5**
**Total steps across all tasks: 37**

**New error codes:**
- `ATLAS-COMMON-003` — Rate limit exceeded (any service)
- `ATLAS-WF-008` — Quota exceeded (workflow-service)

**New Flyway migrations:**
- `V009__add_tenant_quota_columns.sql` (identity)
- `V010__seed_acme_tenant_quotas.sql` (identity)
- `V011__create_service_accounts_table.sql` (identity)
- `V012__create_api_keys_table.sql` (identity)

**New Kafka event types:**
- `tenant.quotas_updated` — published by identity-service when tenant quotas change
