# Plan 3: Worker Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the Worker Service — a stateless step executor that consumes workflow step commands from Kafka, acquires Redis leases for deduplication, executes steps via pluggable executors (strategy pattern), and publishes results back to Kafka.

**Architecture:** Spring Boot 4.0.5 service (port 8083) with NO database. Consumes from workflow.steps.execute Kafka topic, acquires Redis leases (SET NX EX), executes steps via strategy pattern executors, publishes results to workflow.steps.result topic. Supports graceful shutdown.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Spring Kafka, Spring Data Redis, JUnit 5, Testcontainers 2.0

**Depends on:** Plan 1 (common module), Plan 2 (Workflow Service publishes step commands)

**Produces:** Working Worker Service that can execute INTERNAL_COMMAND, HTTP_ACTION, DELAY, and EVENT_WAIT step types with Redis-based lease management.

---

## Task 1: Worker Service Scaffolding

**Files to create:**
- `worker-service/pom.xml`
- `worker-service/src/main/java/com/atlas/worker/WorkerServiceApplication.java`
- `worker-service/src/main/resources/application.yml`
- `worker-service/src/main/java/com/atlas/worker/config/SecurityConfig.java`
- `worker-service/src/main/java/com/atlas/worker/config/JwtConfig.java`
- `worker-service/src/main/java/com/atlas/worker/security/JwtAuthenticationFilter.java`
- `worker-service/src/main/java/com/atlas/worker/security/TenantContext.java`
- `worker-service/src/test/java/com/atlas/worker/TestcontainersConfiguration.java`
- `worker-service/src/test/java/com/atlas/worker/WorkerServiceApplicationTests.java`

### Step 1.1 — `worker-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.atlas</groupId>
        <artifactId>atlas-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>atlas-worker-service</artifactId>
    <name>Atlas Worker Service</name>
    <description>Stateless step executor: consumes step commands, acquires Redis leases, executes via strategy pattern, publishes results</description>

    <properties>
        <jjwt.version>0.13.0</jjwt.version>
    </properties>

    <dependencies>
        <!-- Atlas Common -->
        <dependency>
            <groupId>com.atlas</groupId>
            <artifactId>atlas-common</artifactId>
        </dependency>

        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- NO JPA, NO Flyway, NO PostgreSQL — worker is stateless -->

        <!-- Kafka -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-kafka</artifactId>
        </dependency>

        <!-- AOP / AspectJ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aspectj</artifactId>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Metrics -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Jackson 2 bridge (required by JJWT which still uses Jackson 2) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-jackson2</artifactId>
        </dependency>

        <!-- JWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>

        <!-- REST clients -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-restclient</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-resttestclient</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- Redis Testcontainers via GenericContainer — no dedicated module needed -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <environmentVariables>
                        <DOCKER_HOST>${env.DOCKER_HOST}</DOCKER_HOST>
                        <TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE>${env.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE}</TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE>
                    </environmentVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

### Step 1.2 — `worker-service/src/main/java/com/atlas/worker/WorkerServiceApplication.java`

```java
package com.atlas.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerServiceApplication.class, args);
    }
}
```

### Step 1.3 — `worker-service/src/main/resources/application.yml`

```yaml
server:
  port: 8083

spring:
  application:
    name: worker-service

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: worker-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.atlas.*"
        spring.json.use.type.headers: false
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    listener:
      concurrency: 4

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms

atlas:
  jwt:
    secret: ${ATLAS_JWT_SECRET:change-me-in-production-must-be-at-least-32-chars}
  worker:
    id: ${WORKER_ID:${random.uuid}}
    lease-timeout-seconds: 30
    drain-timeout-seconds: 30

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      service: worker-service

logging:
  pattern:
    console: '{"timestamp":"%d{yyyy-MM-dd HH:mm:ss.SSS}","level":"%level","service":"worker-service","traceId":"%X{traceId}","correlationId":"%X{correlationId}","tenantId":"%X{tenantId}","message":"%message"}%n'
```

### Step 1.4 — `worker-service/src/main/java/com/atlas/worker/config/SecurityConfig.java`

```java
package com.atlas.worker.config;

import com.atlas.worker.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### Step 1.5 — `worker-service/src/main/java/com/atlas/worker/config/JwtConfig.java`

```java
package com.atlas.worker.config;

import com.atlas.common.security.JwtTokenParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtTokenParser jwtTokenParser(@Value("${atlas.jwt.secret}") String secret) {
        return new JwtTokenParser(secret);
    }
}
```

### Step 1.6 — `worker-service/src/main/java/com/atlas/worker/security/TenantContext.java`

```java
package com.atlas.worker.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class TenantContext {

    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
```

### Step 1.7 — `worker-service/src/main/java/com/atlas/worker/security/JwtAuthenticationFilter.java`

```java
package com.atlas.worker.security;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.common.security.InvalidTokenException;
import com.atlas.common.security.JwtTokenParser;
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

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenParser jwtTokenParser;
    private final TenantContext tenantContext;

    public JwtAuthenticationFilter(JwtTokenParser jwtTokenParser, TenantContext tenantContext) {
        this.jwtTokenParser = jwtTokenParser;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());

            try {
                AuthenticatedPrincipal principal = jwtTokenParser.parse(token);

                var authorities = principal.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                tenantContext.setTenantId(principal.tenantId());
            } catch (InvalidTokenException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### Step 1.8 — `worker-service/src/test/java/com/atlas/worker/TestcontainersConfiguration.java`

No PostgreSQL — only Kafka and Redis.

```java
package com.atlas.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");
    }

    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
    }
}
```

### Step 1.9 — `worker-service/src/test/java/com/atlas/worker/WorkerServiceApplicationTests.java`

```java
package com.atlas.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WorkerServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

### Step 1.10 — Verify

```bash
cd worker-service && mvn clean test -Dtest=WorkerServiceApplicationTests
```

**Git commit:** `feat(worker): scaffold worker-service with Spring Boot 4.0.5, Kafka, Redis, JWT security (no DB)`

---

## Task 2: Redis Lease Manager

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/lease/LeaseManager.java`
- `worker-service/src/main/java/com/atlas/worker/lease/RedisLeaseManager.java`
- `worker-service/src/test/java/com/atlas/worker/lease/RedisLeaseManagerTest.java`

### Step 2.1 — `worker-service/src/main/java/com/atlas/worker/lease/LeaseManager.java`

```java
package com.atlas.worker.lease;

/**
 * Manages ephemeral Redis leases for step executions.
 * Prevents duplicate execution when multiple workers consume the same command.
 */
public interface LeaseManager {

    /**
     * Attempt to acquire a lease for the given step execution.
     *
     * @param stepExecutionId unique ID of the step execution
     * @param workerId        ID of the worker attempting acquisition
     * @param timeoutSeconds  lease TTL in seconds; heartbeat must extend before expiry
     * @return true if the lease was acquired, false if already held by another worker
     */
    boolean acquireLease(String stepExecutionId, String workerId, int timeoutSeconds);

    /**
     * Release a lease. Only succeeds if the caller is the current lease owner.
     * Implemented atomically with a Lua script to prevent accidental release of
     * a lease re-acquired by another worker after expiry.
     *
     * @param stepExecutionId unique ID of the step execution
     * @param workerId        ID of the worker releasing the lease
     * @return true if the lease was released, false if not owned by this worker
     */
    boolean releaseLease(String stepExecutionId, String workerId);

    /**
     * Extend the TTL of a currently-held lease (heartbeat).
     *
     * @param stepExecutionId unique ID of the step execution
     * @param workerId        ID of the worker holding the lease
     * @param timeoutSeconds  new TTL in seconds
     * @return true if extended, false if lease not held by this worker
     */
    boolean extendLease(String stepExecutionId, String workerId, int timeoutSeconds);
}
```

### Step 2.2 — `worker-service/src/main/java/com/atlas/worker/lease/RedisLeaseManager.java`

```java
package com.atlas.worker.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisLeaseManager implements LeaseManager {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaseManager.class);

    private static final String KEY_PREFIX = "step:";

    /**
     * Atomic release: only DELETE the key if its value equals the expected workerId.
     * Returns 1 if deleted, 0 if not owned.
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("DEL", KEYS[1])
            else
                return 0
            end
            """, Long.class);

    /**
     * Atomic extend: only EXPIRE the key if its value equals the expected workerId.
     * Returns 1 if extended, 0 if not owned.
     */
    private static final DefaultRedisScript<Long> EXTEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("EXPIRE", KEYS[1], ARGV[2])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLeaseManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquireLease(String stepExecutionId, String workerId, int timeoutSeconds) {
        String key = KEY_PREFIX + stepExecutionId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, workerId, Duration.ofSeconds(timeoutSeconds));
        boolean result = Boolean.TRUE.equals(acquired);
        if (result) {
            log.debug("Lease acquired: stepExecutionId={} workerId={}", stepExecutionId, workerId);
        } else {
            log.debug("Lease conflict: stepExecutionId={} workerId={}", stepExecutionId, workerId);
        }
        return result;
    }

    @Override
    public boolean releaseLease(String stepExecutionId, String workerId) {
        String key = KEY_PREFIX + stepExecutionId;
        Long result = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), workerId);
        boolean released = Long.valueOf(1L).equals(result);
        if (released) {
            log.debug("Lease released: stepExecutionId={} workerId={}", stepExecutionId, workerId);
        } else {
            log.warn("Lease release failed (not owner): stepExecutionId={} workerId={}", stepExecutionId, workerId);
        }
        return released;
    }

    @Override
    public boolean extendLease(String stepExecutionId, String workerId, int timeoutSeconds) {
        String key = KEY_PREFIX + stepExecutionId;
        Long result = redisTemplate.execute(EXTEND_SCRIPT, List.of(key), workerId, String.valueOf(timeoutSeconds));
        boolean extended = Long.valueOf(1L).equals(result);
        if (!extended) {
            log.warn("Lease extend failed (not owner or expired): stepExecutionId={} workerId={}", stepExecutionId, workerId);
        }
        return extended;
    }
}
```

### Step 2.3 — `worker-service/src/test/java/com/atlas/worker/lease/RedisLeaseManagerTest.java`

```java
package com.atlas.worker.lease;

import com.atlas.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisLeaseManagerTest {

    @Autowired
    private RedisLeaseManager leaseManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String stepExecutionId;
    private String workerA;
    private String workerB;

    @BeforeEach
    void setUp() {
        stepExecutionId = UUID.randomUUID().toString();
        workerA = "worker-a-" + UUID.randomUUID();
        workerB = "worker-b-" + UUID.randomUUID();
        // Clean up any residual keys
        redisTemplate.delete("step:" + stepExecutionId);
    }

    @Test
    void acquireLease_succeeds_whenKeyNotPresent() {
        boolean acquired = leaseManager.acquireLease(stepExecutionId, workerA, 30);
        assertThat(acquired).isTrue();
    }

    @Test
    void acquireLease_fails_whenAlreadyHeldByAnotherWorker() {
        leaseManager.acquireLease(stepExecutionId, workerA, 30);

        boolean acquired = leaseManager.acquireLease(stepExecutionId, workerB, 30);
        assertThat(acquired).isFalse();
    }

    @Test
    void acquireLease_fails_whenAlreadyHeldBySameWorker() {
        leaseManager.acquireLease(stepExecutionId, workerA, 30);

        // SET NX returns false even for same workerId — idempotency not a goal here
        boolean acquired = leaseManager.acquireLease(stepExecutionId, workerA, 30);
        assertThat(acquired).isFalse();
    }

    @Test
    void releaseLease_byOwner_succeeds() {
        leaseManager.acquireLease(stepExecutionId, workerA, 30);

        boolean released = leaseManager.releaseLease(stepExecutionId, workerA);
        assertThat(released).isTrue();

        // Key should no longer exist
        assertThat(redisTemplate.hasKey("step:" + stepExecutionId)).isFalse();
    }

    @Test
    void releaseLease_byNonOwner_fails() {
        leaseManager.acquireLease(stepExecutionId, workerA, 30);

        boolean released = leaseManager.releaseLease(stepExecutionId, workerB);
        assertThat(released).isFalse();

        // Original lease must still be held by workerA
        String currentOwner = redisTemplate.opsForValue().get("step:" + stepExecutionId);
        assertThat(currentOwner).isEqualTo(workerA);
    }

    @Test
    void extendLease_byOwner_succeeds() {
        leaseManager.acquireLease(stepExecutionId, workerA, 5);

        boolean extended = leaseManager.extendLease(stepExecutionId, workerA, 60);
        assertThat(extended).isTrue();

        Long ttl = redisTemplate.getExpire("step:" + stepExecutionId);
        assertThat(ttl).isGreaterThan(30L);
    }

    @Test
    void extendLease_byNonOwner_fails() {
        leaseManager.acquireLease(stepExecutionId, workerA, 30);

        boolean extended = leaseManager.extendLease(stepExecutionId, workerB, 60);
        assertThat(extended).isFalse();
    }

    @Test
    void releaseLease_whenNoLease_fails() {
        boolean released = leaseManager.releaseLease(stepExecutionId, workerA);
        assertThat(released).isFalse();
    }
}
```

### Step 2.4 — Verify

```bash
cd worker-service && mvn clean test -Dtest=RedisLeaseManagerTest
```

**Git commit:** `feat(worker): add Redis lease manager with atomic acquire/release/extend via Lua scripts`

---

## Task 3: Step Executors (Strategy Pattern)

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/executor/StepCommand.java`
- `worker-service/src/main/java/com/atlas/worker/executor/StepResult.java`
- `worker-service/src/main/java/com/atlas/worker/executor/StepOutcome.java`
- `worker-service/src/main/java/com/atlas/worker/executor/StepExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/StepExecutorRegistry.java`
- `worker-service/src/main/java/com/atlas/worker/executor/InternalCommandExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/HttpActionExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/DelayStepExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/EventWaitExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/CompensationExecutor.java`
- `worker-service/src/main/java/com/atlas/worker/executor/CommandHandler.java`
- `worker-service/src/test/java/com/atlas/worker/executor/InternalCommandExecutorTest.java`
- `worker-service/src/test/java/com/atlas/worker/executor/DelayStepExecutorTest.java`
- `worker-service/src/test/java/com/atlas/worker/executor/EventWaitExecutorTest.java`
- `worker-service/src/test/java/com/atlas/worker/executor/HttpActionExecutorTest.java`
- `worker-service/src/test/java/com/atlas/worker/executor/StepExecutorRegistryTest.java`

### Step 3.1 — `worker-service/src/main/java/com/atlas/worker/executor/StepOutcome.java`

```java
package com.atlas.worker.executor;

public enum StepOutcome {
    SUCCEEDED,
    FAILED,
    DELAY_REQUESTED,
    WAITING
}
```

### Step 3.2 — `worker-service/src/main/java/com/atlas/worker/executor/StepCommand.java`

```java
package com.atlas.worker.executor;

import java.util.Map;

/**
 * Command published by the Workflow Service to workflow.steps.execute.
 * Contains everything the Worker needs to execute one step in isolation.
 */
public record StepCommand(
        String stepExecutionId,
        String executionId,
        String tenantId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input,
        long timeoutMs,
        boolean isCompensation,
        String compensationFor
) {
}
```

### Step 3.3 — `worker-service/src/main/java/com/atlas/worker/executor/StepResult.java`

```java
package com.atlas.worker.executor;

import java.util.Map;

/**
 * Result published by the Worker Service to workflow.steps.result.
 */
public record StepResult(
        String stepExecutionId,
        String executionId,
        String tenantId,
        StepOutcome outcome,
        int attempt,
        Map<String, Object> output,
        String error,
        boolean nonRetryable
) {

    public static StepResult succeeded(StepCommand command, Map<String, Object> output) {
        return new StepResult(
                command.stepExecutionId(),
                command.executionId(),
                command.tenantId(),
                StepOutcome.SUCCEEDED,
                command.attempt(),
                output,
                null,
                false
        );
    }

    public static StepResult failed(StepCommand command, String error, boolean nonRetryable) {
        return new StepResult(
                command.stepExecutionId(),
                command.executionId(),
                command.tenantId(),
                StepOutcome.FAILED,
                command.attempt(),
                Map.of(),
                error,
                nonRetryable
        );
    }

    public static StepResult delayRequested(StepCommand command) {
        return new StepResult(
                command.stepExecutionId(),
                command.executionId(),
                command.tenantId(),
                StepOutcome.DELAY_REQUESTED,
                command.attempt(),
                command.input(),  // pass-through delay_ms from input
                null,
                false
        );
    }

    public static StepResult waiting(StepCommand command) {
        return new StepResult(
                command.stepExecutionId(),
                command.executionId(),
                command.tenantId(),
                StepOutcome.WAITING,
                command.attempt(),
                Map.of(),
                null,
                false
        );
    }
}
```

### Step 3.4 — `worker-service/src/main/java/com/atlas/worker/executor/StepExecutor.java`

```java
package com.atlas.worker.executor;

/**
 * Strategy interface for step execution.
 * Each implementation handles one step type.
 */
public interface StepExecutor {

    /**
     * Returns the step type string this executor handles (e.g. "INTERNAL_COMMAND").
     */
    String stepType();

    /**
     * Execute the step described by the command and return a result.
     * Implementations must not throw — all failures are encoded in StepResult.
     */
    StepResult execute(StepCommand command);
}
```

### Step 3.5 — `worker-service/src/main/java/com/atlas/worker/executor/CommandHandler.java`

```java
package com.atlas.worker.executor;

import java.util.Map;

/**
 * Pluggable handler for a named internal command.
 * Register implementations as Spring beans — InternalCommandExecutor discovers them automatically.
 */
public interface CommandHandler {

    String commandName();

    Map<String, Object> handle(Map<String, Object> input);
}
```

### Step 3.6 — `worker-service/src/main/java/com/atlas/worker/executor/InternalCommandExecutor.java`

Handles `INTERNAL_COMMAND` step type. Discovers registered `CommandHandler` beans by name. Supports failure injection via `fail_at_step` / `failure_type` / `fail_after_attempts` keys in `input` — used by demo workflows to exercise retry and compensation logic.

```java
package com.atlas.worker.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InternalCommandExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(InternalCommandExecutor.class);

    private static final String STEP_TYPE = "INTERNAL_COMMAND";

    private final Map<String, CommandHandler> handlersByName;

    public InternalCommandExecutor(List<CommandHandler> handlers) {
        this.handlersByName = handlers.stream()
                .collect(Collectors.toMap(CommandHandler::commandName, h -> h));
    }

    @Override
    public String stepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        String commandName = (String) command.input().get("command");
        if (commandName == null) {
            return StepResult.failed(command, "Missing 'command' key in input", true);
        }

        // Failure injection support for demo workflows
        StepResult injectedFailure = checkFailureInjection(command);
        if (injectedFailure != null) {
            return injectedFailure;
        }

        CommandHandler handler = handlersByName.get(commandName);
        if (handler == null) {
            return StepResult.failed(command,
                    "Unknown command: " + commandName + ". Registered: " + handlersByName.keySet(),
                    true);
        }

        try {
            Map<String, Object> output = handler.handle(command.input());
            return StepResult.succeeded(command, output);
        } catch (Exception e) {
            log.error("Command handler threw exception: command={} stepExecutionId={}",
                    commandName, command.stepExecutionId(), e);
            return StepResult.failed(command, e.getMessage(), false);
        }
    }

    /**
     * Checks input for failure injection keys used by demo/test workflows.
     * Keys:
     *   fail_at_step   — step name to fail on (string)
     *   failure_type   — TRANSIENT or PERMANENT
     *   fail_after_attempts — only fail for attempts <= this value
     */
    private StepResult checkFailureInjection(StepCommand command) {
        Object failAtStep = command.input().get("fail_at_step");
        if (failAtStep == null || !failAtStep.equals(command.stepName())) {
            return null;
        }

        String failureType = (String) command.input().getOrDefault("failure_type", "TRANSIENT");
        boolean nonRetryable = "PERMANENT".equalsIgnoreCase(failureType);

        Object failAfterAttempts = command.input().get("fail_after_attempts");
        if (failAfterAttempts instanceof Number limit) {
            if (command.attempt() > limit.intValue()) {
                return null; // past the injection window — proceed normally
            }
        }

        return StepResult.failed(command,
                "Injected " + failureType + " failure at step " + command.stepName(),
                nonRetryable);
    }
}
```

### Step 3.7 — `worker-service/src/main/java/com/atlas/worker/executor/HttpActionExecutor.java`

Stub for now — HTTP_ACTION step type executes an HTTP call using RestClient. Full URL/method/headers/body come from `input`. Marked as a stub; the structure is correct and wired, full HTTP logic is straightforward to add.

```java
package com.atlas.worker.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpActionExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpActionExecutor.class);

    private static final String STEP_TYPE = "HTTP_ACTION";

    private final RestClient.Builder restClientBuilder;

    public HttpActionExecutor(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public String stepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        String url = (String) command.input().get("url");
        if (url == null) {
            return StepResult.failed(command, "Missing 'url' key in input for HTTP_ACTION", true);
        }

        String method = (String) command.input().getOrDefault("method", "GET");
        long timeoutMs = command.timeoutMs() > 0 ? command.timeoutMs() : 10_000L;

        try {
            RestClient client = restClientBuilder
                    .baseUrl(url)
                    .build();

            // Build and execute the request
            String responseBody = switch (method.toUpperCase()) {
                case "GET" -> client.get()
                        .uri("")
                        .retrieve()
                        .body(String.class);
                case "POST" -> {
                    Object requestBody = command.input().get("body");
                    yield client.post()
                            .uri("")
                            .body(requestBody != null ? requestBody : Map.of())
                            .retrieve()
                            .body(String.class);
                }
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            };

            return StepResult.succeeded(command, Map.of("response_body", responseBody != null ? responseBody : ""));
        } catch (Exception e) {
            log.error("HTTP action failed: url={} method={} stepExecutionId={}", url, method, command.stepExecutionId(), e);
            return StepResult.failed(command, "HTTP action failed: " + e.getMessage(), false);
        }
    }
}
```

### Step 3.8 — `worker-service/src/main/java/com/atlas/worker/executor/DelayStepExecutor.java`

Does NOT block a worker thread. Returns `DELAY_REQUESTED` immediately with the delay configuration. The Workflow Service schedules the wake-up via Redis `ZADD`.

```java
package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

@Component
public class DelayStepExecutor implements StepExecutor {

    private static final String STEP_TYPE = "DELAY";

    @Override
    public String stepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        Object delayMs = command.input().get("delay_ms");
        if (delayMs == null) {
            return StepResult.failed(command, "Missing 'delay_ms' key in input for DELAY step", true);
        }
        // Pass input through as output — Workflow Service reads delay_ms from output
        return StepResult.delayRequested(command);
    }
}
```

### Step 3.9 — `worker-service/src/main/java/com/atlas/worker/executor/EventWaitExecutor.java`

Returns `WAITING` immediately. Workflow Service transitions execution to `WAITING` state. The step advances when an external event arrives via API or Kafka, or when a timeout fires.

```java
package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

@Component
public class EventWaitExecutor implements StepExecutor {

    private static final String STEP_TYPE = "EVENT_WAIT";

    @Override
    public String stepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        return StepResult.waiting(command);
    }
}
```

### Step 3.10 — `worker-service/src/main/java/com/atlas/worker/executor/CompensationExecutor.java`

Same execution logic as `InternalCommandExecutor` but tagged as compensation. Delegates to `InternalCommandExecutor` for the actual handler lookup and execution.

```java
package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

@Component
public class CompensationExecutor implements StepExecutor {

    private static final String STEP_TYPE = "COMPENSATION";

    private final InternalCommandExecutor delegate;

    public CompensationExecutor(InternalCommandExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public String stepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        // Delegate to internal command execution — compensation steps are
        // registered handlers that reverse the forward step's side effects
        return delegate.execute(command);
    }
}
```

### Step 3.11 — `worker-service/src/main/java/com/atlas/worker/executor/StepExecutorRegistry.java`

```java
package com.atlas.worker.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Discovers all StepExecutor beans and routes step commands to the correct executor by type.
 */
@Component
public class StepExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(StepExecutorRegistry.class);

    private final Map<String, StepExecutor> executorsByType;

    public StepExecutorRegistry(List<StepExecutor> executors) {
        this.executorsByType = executors.stream()
                .collect(Collectors.toMap(StepExecutor::stepType, e -> e));
        log.info("Registered step executors: {}", executorsByType.keySet());
    }

    /**
     * Execute a step command using the registered executor for its step type.
     * If no executor is registered for the type, returns a non-retryable FAILED result
     * (poison message handling — unknown types go straight to dead-letter).
     */
    public StepResult execute(StepCommand command) {
        StepExecutor executor = executorsByType.get(command.stepType());
        if (executor == null) {
            log.error("No executor registered for stepType={} stepExecutionId={}",
                    command.stepType(), command.stepExecutionId());
            return StepResult.failed(command,
                    "Unknown step type: " + command.stepType(), true);
        }
        return executor.execute(command);
    }

    public boolean hasExecutor(String stepType) {
        return executorsByType.containsKey(stepType);
    }
}
```

### Step 3.12 — `worker-service/src/test/java/com/atlas/worker/executor/InternalCommandExecutorTest.java`

```java
package com.atlas.worker.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCommandExecutorTest {

    private InternalCommandExecutor executor;

    @BeforeEach
    void setUp() {
        CommandHandler echoHandler = new CommandHandler() {
            @Override
            public String commandName() { return "echo"; }

            @Override
            public Map<String, Object> handle(Map<String, Object> input) {
                return Map.of("echo", input.getOrDefault("message", ""));
            }
        };

        CommandHandler failingHandler = new CommandHandler() {
            @Override
            public String commandName() { return "always_fails"; }

            @Override
            public Map<String, Object> handle(Map<String, Object> input) {
                throw new RuntimeException("Handler always fails");
            }
        };

        executor = new InternalCommandExecutor(List.of(echoHandler, failingHandler));
    }

    private StepCommand command(String stepName, Map<String, Object> input) {
        return new StepCommand(
                "step-exec-1", "exec-1", "tenant-1",
                stepName, "INTERNAL_COMMAND", 1,
                input, 5000L, false, null
        );
    }

    @Test
    void execute_succeeds_withRegisteredCommand() {
        StepCommand cmd = command("echo_step", Map.of("command", "echo", "message", "hello"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.output()).containsEntry("echo", "hello");
    }

    @Test
    void execute_fails_withMissingCommandKey() {
        StepCommand cmd = command("some_step", Map.of("other_key", "value"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("Missing 'command' key");
    }

    @Test
    void execute_fails_withUnknownCommand() {
        StepCommand cmd = command("some_step", Map.of("command", "not_registered"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("Unknown command");
    }

    @Test
    void execute_fails_whenHandlerThrows() {
        StepCommand cmd = command("failing_step", Map.of("command", "always_fails"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isFalse(); // handler exceptions are retryable
    }

    @Test
    void execute_injectsTransientFailure_whenConfigured() {
        StepCommand cmd = new StepCommand(
                "step-exec-2", "exec-2", "tenant-1",
                "payment_step", "INTERNAL_COMMAND", 1,
                Map.of("command", "echo", "fail_at_step", "payment_step", "failure_type", "TRANSIENT"),
                5000L, false, null
        );

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isFalse();
        assertThat(result.error()).contains("TRANSIENT");
    }

    @Test
    void execute_injectsPermanentFailure_whenConfigured() {
        StepCommand cmd = new StepCommand(
                "step-exec-3", "exec-3", "tenant-1",
                "payment_step", "INTERNAL_COMMAND", 1,
                Map.of("command", "echo", "fail_at_step", "payment_step", "failure_type", "PERMANENT"),
                5000L, false, null
        );

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
    }

    @Test
    void execute_skipsInjection_afterFailAfterAttemptsExceeded() {
        StepCommand cmd = new StepCommand(
                "step-exec-4", "exec-4", "tenant-1",
                "payment_step", "INTERNAL_COMMAND", 3, // attempt 3
                Map.of("command", "echo", "message", "hi",
                        "fail_at_step", "payment_step",
                        "fail_after_attempts", 2), // only fail for attempts <= 2
                5000L, false, null
        );

        StepResult result = executor.execute(cmd);

        // Attempt 3 exceeds fail_after_attempts=2, so injection is skipped
        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
    }
}
```

### Step 3.13 — `worker-service/src/test/java/com/atlas/worker/executor/DelayStepExecutorTest.java`

```java
package com.atlas.worker.executor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelayStepExecutorTest {

    private final DelayStepExecutor executor = new DelayStepExecutor();

    private StepCommand command(Map<String, Object> input) {
        return new StepCommand(
                "step-exec-1", "exec-1", "tenant-1",
                "wait_step", "DELAY", 1,
                input, 5000L, false, null
        );
    }

    @Test
    void execute_returnsDelayRequested_withDelayMs() {
        StepCommand cmd = command(Map.of("delay_ms", 5000));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.DELAY_REQUESTED);
        assertThat(result.output()).containsKey("delay_ms");
    }

    @Test
    void execute_failsNonRetryable_whenDelayMsMissing() {
        StepCommand cmd = command(Map.of());

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("delay_ms");
    }
}
```

### Step 3.14 — `worker-service/src/test/java/com/atlas/worker/executor/EventWaitExecutorTest.java`

```java
package com.atlas.worker.executor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventWaitExecutorTest {

    private final EventWaitExecutor executor = new EventWaitExecutor();

    @Test
    void execute_returnsWaiting_immediately() {
        StepCommand cmd = new StepCommand(
                "step-exec-1", "exec-1", "tenant-1",
                "wait_for_payment", "EVENT_WAIT", 1,
                Map.of("event_type", "payment.confirmed"), 60_000L, false, null
        );

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.WAITING);
        assertThat(result.error()).isNull();
        assertThat(result.nonRetryable()).isFalse();
    }
}
```

### Step 3.15 — `worker-service/src/test/java/com/atlas/worker/executor/HttpActionExecutorTest.java`

```java
package com.atlas.worker.executor;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpActionExecutorTest {

    private final HttpActionExecutor executor =
            new HttpActionExecutor(RestClient.builder());

    private StepCommand command(Map<String, Object> input) {
        return new StepCommand(
                "step-exec-1", "exec-1", "tenant-1",
                "call_external", "HTTP_ACTION", 1,
                input, 5000L, false, null
        );
    }

    @Test
    void execute_failsNonRetryable_whenUrlMissing() {
        StepCommand cmd = command(Map.of("method", "GET"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("url");
    }

    @Test
    void execute_fails_whenTargetUnreachable() {
        StepCommand cmd = command(Map.of(
                "url", "http://localhost:19999/nonexistent",
                "method", "GET"
        ));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isFalse(); // connection errors are retryable
    }
}
```

### Step 3.16 — `worker-service/src/test/java/com/atlas/worker/executor/StepExecutorRegistryTest.java`

```java
package com.atlas.worker.executor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutorRegistryTest {

    private StepExecutorRegistry registryWith(StepExecutor... executors) {
        return new StepExecutorRegistry(List.of(executors));
    }

    @Test
    void execute_routesToCorrectExecutor() {
        StepExecutor echoExecutor = new StepExecutor() {
            @Override public String stepType() { return "ECHO"; }
            @Override public StepResult execute(StepCommand command) {
                return StepResult.succeeded(command, Map.of("echoed", true));
            }
        };

        StepExecutorRegistry registry = registryWith(echoExecutor);
        StepCommand cmd = new StepCommand(
                "s1", "e1", "t1", "echo_step", "ECHO", 1,
                Map.of(), 1000L, false, null
        );

        StepResult result = registry.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.output()).containsEntry("echoed", true);
    }

    @Test
    void execute_returnsNonRetryableFailed_forUnknownType() {
        StepExecutorRegistry registry = registryWith(); // empty
        StepCommand cmd = new StepCommand(
                "s1", "e1", "t1", "some_step", "UNKNOWN_TYPE", 1,
                Map.of(), 1000L, false, null
        );

        StepResult result = registry.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("Unknown step type");
    }

    @Test
    void hasExecutor_returnsTrue_whenRegistered() {
        StepExecutor fakeExecutor = new StepExecutor() {
            @Override public String stepType() { return "MY_TYPE"; }
            @Override public StepResult execute(StepCommand command) { return null; }
        };
        StepExecutorRegistry registry = registryWith(fakeExecutor);
        assertThat(registry.hasExecutor("MY_TYPE")).isTrue();
        assertThat(registry.hasExecutor("OTHER")).isFalse();
    }
}
```

### Step 3.17 — Verify

```bash
cd worker-service && mvn clean test -Dtest="InternalCommandExecutorTest,DelayStepExecutorTest,EventWaitExecutorTest,HttpActionExecutorTest,StepExecutorRegistryTest"
```

**Git commit:** `feat(worker): add step executors with strategy pattern (INTERNAL_COMMAND, HTTP_ACTION, DELAY, EVENT_WAIT, COMPENSATION)`

---

## Task 4: Step Command Consumer

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/consumer/StepCommandConsumer.java`
- `worker-service/src/test/java/com/atlas/worker/consumer/StepCommandConsumerIntegrationTest.java`

### Step 4.1 — `worker-service/src/main/java/com/atlas/worker/consumer/StepCommandConsumer.java`

```java
package com.atlas.worker.consumer;

import com.atlas.worker.executor.StepCommand;
import com.atlas.worker.executor.StepExecutorRegistry;
import com.atlas.worker.executor.StepResult;
import com.atlas.worker.lease.LeaseManager;
import com.atlas.worker.reporter.StepResultPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class StepCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(StepCommandConsumer.class);

    private static final String TOPIC = "workflow.steps.execute";

    private final LeaseManager leaseManager;
    private final StepExecutorRegistry executorRegistry;
    private final StepResultPublisher resultPublisher;
    private final String workerId;
    private final int leaseTimeoutSeconds;

    private final Counter leaseAcquiredCounter;
    private final Counter leaseConflictCounter;
    private final Counter stepSuccessCounter;
    private final Counter stepFailureCounter;
    private final Timer stepDurationTimer;

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());

    public StepCommandConsumer(
            LeaseManager leaseManager,
            StepExecutorRegistry executorRegistry,
            StepResultPublisher resultPublisher,
            MeterRegistry meterRegistry,
            @Value("${atlas.worker.id}") String workerId,
            @Value("${atlas.worker.lease-timeout-seconds:30}") int leaseTimeoutSeconds) {

        this.leaseManager = leaseManager;
        this.executorRegistry = executorRegistry;
        this.resultPublisher = resultPublisher;
        this.workerId = workerId;
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;

        this.leaseAcquiredCounter = Counter.builder("atlas.worker.lease.acquired")
                .description("Number of leases successfully acquired")
                .register(meterRegistry);
        this.leaseConflictCounter = Counter.builder("atlas.worker.lease.conflict")
                .description("Number of lease acquisition conflicts")
                .register(meterRegistry);
        this.stepSuccessCounter = Counter.builder("atlas.worker.step.success")
                .description("Number of steps completed successfully")
                .register(meterRegistry);
        this.stepFailureCounter = Counter.builder("atlas.worker.step.failure")
                .description("Number of steps that failed")
                .register(meterRegistry);
        this.stepDurationTimer = Timer.builder("atlas.worker.step.duration")
                .description("Step execution duration")
                .register(meterRegistry);
    }

    @KafkaListener(topics = TOPIC, groupId = "worker-service")
    public void consume(
            @Payload StepCommand command,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment acknowledgment) {

        log.info("Received step command: stepExecutionId={} stepType={} attempt={}",
                command.stepExecutionId(), command.stepType(), command.attempt());

        // Acquire Redis lease — skip if another worker already has it
        boolean leaseAcquired = leaseManager.acquireLease(
                command.stepExecutionId(), workerId, leaseTimeoutSeconds);

        if (!leaseAcquired) {
            log.info("Skipping step — lease held by another worker: stepExecutionId={}",
                    command.stepExecutionId());
            leaseConflictCounter.increment();
            acknowledgment.acknowledge();
            return;
        }

        leaseAcquiredCounter.increment();

        // Start heartbeat to extend lease during execution
        ScheduledFuture<?> heartbeat = startHeartbeat(command.stepExecutionId());

        StepResult result;
        try {
            result = stepDurationTimer.recordCallable(() -> executorRegistry.execute(command));
        } catch (Exception e) {
            log.error("Unexpected exception during step execution: stepExecutionId={}",
                    command.stepExecutionId(), e);
            result = StepResult.failed(command, "Unexpected error: " + e.getMessage(), false);
        } finally {
            heartbeat.cancel(false);
        }

        // Publish result before releasing lease
        try {
            resultPublisher.publish(result);
        } catch (Exception e) {
            log.error("Failed to publish step result: stepExecutionId={}", command.stepExecutionId(), e);
            // Do not rethrow — Kafka template has built-in retry; lease will be released
        }

        // Track metrics
        if (result.outcome().name().equals("SUCCEEDED")) {
            stepSuccessCounter.increment();
        } else if (result.outcome().name().equals("FAILED")) {
            stepFailureCounter.increment();
        }

        // Release lease after result is published
        leaseManager.releaseLease(command.stepExecutionId(), workerId);

        acknowledgment.acknowledge();

        log.info("Step complete: stepExecutionId={} outcome={}", command.stepExecutionId(), result.outcome());
    }

    private ScheduledFuture<?> startHeartbeat(String stepExecutionId) {
        int heartbeatIntervalSeconds = Math.max(1, leaseTimeoutSeconds / 3);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            boolean extended = leaseManager.extendLease(stepExecutionId, workerId, leaseTimeoutSeconds);
            if (!extended) {
                log.warn("Heartbeat failed to extend lease: stepExecutionId={}", stepExecutionId);
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }
}
```

### Step 4.2 — `worker-service/src/test/java/com/atlas/worker/consumer/StepCommandConsumerIntegrationTest.java`

```java
package com.atlas.worker.consumer;

import com.atlas.worker.TestcontainersConfiguration;
import com.atlas.worker.executor.StepCommand;
import com.atlas.worker.executor.StepOutcome;
import com.atlas.worker.executor.StepResult;
import com.atlas.worker.reporter.StepResultPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.kafka.listener.ack-mode=manual",
        "atlas.worker.lease-timeout-seconds=10"
})
class StepCommandConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private StepResultPublisher resultPublisher;

    @Test
    void consumer_executesStep_andPublishesResult() throws Exception {
        AtomicReference<StepResult> capturedResult = new AtomicReference<>();
        doAnswer(inv -> {
            capturedResult.set(inv.getArgument(0));
            return null;
        }).when(resultPublisher).publish(any(StepResult.class));

        StepCommand command = new StepCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "tenant-1",
                "echo_step",
                "INTERNAL_COMMAND",
                1,
                Map.of("command", "echo", "message", "integration-test"),
                5000L,
                false,
                null
        );

        kafkaTemplate.send(new ProducerRecord<>("workflow.steps.execute",
                command.tenantId(), command));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    verify(resultPublisher).publish(any(StepResult.class));
                    StepResult result = capturedResult.get();
                    assertThat(result).isNotNull();
                    assertThat(result.stepExecutionId()).isEqualTo(command.stepExecutionId());
                    assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
                });
    }

    @Test
    void consumer_publishesFailedResult_forUnknownStepType() throws Exception {
        AtomicReference<StepResult> capturedResult = new AtomicReference<>();
        doAnswer(inv -> {
            capturedResult.set(inv.getArgument(0));
            return null;
        }).when(resultPublisher).publish(any(StepResult.class));

        StepCommand command = new StepCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "tenant-1",
                "mystery_step",
                "UNKNOWN_TYPE",
                1,
                Map.of(),
                5000L,
                false,
                null
        );

        kafkaTemplate.send(new ProducerRecord<>("workflow.steps.execute",
                command.tenantId(), command));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    StepResult result = capturedResult.get();
                    assertThat(result).isNotNull();
                    assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
                    assertThat(result.nonRetryable()).isTrue();
                });
    }
}
```

### Step 4.3 — Verify

```bash
cd worker-service && mvn clean test -Dtest=StepCommandConsumerIntegrationTest
```

**Git commit:** `feat(worker): add Kafka step command consumer with lease acquisition, heartbeat, and result publishing`

---

## Task 5: Result Publisher

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/reporter/StepResultPublisher.java`
- `worker-service/src/test/java/com/atlas/worker/reporter/StepResultPublisherIntegrationTest.java`

### Step 5.1 — `worker-service/src/main/java/com/atlas/worker/reporter/StepResultPublisher.java`

```java
package com.atlas.worker.reporter;

import com.atlas.worker.executor.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class StepResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(StepResultPublisher.class);

    private static final String TOPIC = "workflow.steps.result";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public StepResultPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish a step result to workflow.steps.result, keyed by tenantId for
     * consistent partition routing. The Workflow Service consumes this topic
     * and advances the execution state machine.
     */
    public void publish(StepResult result) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(TOPIC, result.tenantId(), result);

        future.whenComplete((sendResult, ex) -> {
            if (ex != null) {
                log.error("Failed to publish step result: stepExecutionId={} outcome={}",
                        result.stepExecutionId(), result.outcome(), ex);
            } else {
                log.debug("Step result published: stepExecutionId={} outcome={} partition={} offset={}",
                        result.stepExecutionId(), result.outcome(),
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Publish and block until the broker acknowledges.
     * Used by graceful shutdown to ensure in-flight results are flushed.
     */
    public void publishSync(StepResult result) throws Exception {
        kafkaTemplate.send(TOPIC, result.tenantId(), result).get();
        log.info("Step result published (sync): stepExecutionId={} outcome={}",
                result.stepExecutionId(), result.outcome());
    }
}
```

### Step 5.2 — `worker-service/src/test/java/com/atlas/worker/reporter/StepResultPublisherIntegrationTest.java`

```java
package com.atlas.worker.reporter;

import com.atlas.worker.TestcontainersConfiguration;
import com.atlas.worker.executor.StepCommand;
import com.atlas.worker.executor.StepOutcome;
import com.atlas.worker.executor.StepResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StepResultPublisherIntegrationTest {

    @Autowired
    private StepResultPublisher publisher;

    private final BlockingQueue<StepResult> received = new LinkedBlockingQueue<>();

    @KafkaListener(topics = "workflow.steps.result", groupId = "test-result-consumer")
    public void onResult(StepResult result) {
        received.add(result);
    }

    @Test
    void publish_sendsResultToCorrectTopic() throws InterruptedException {
        StepCommand command = new StepCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "tenant-integration-test",
                "some_step",
                "INTERNAL_COMMAND",
                1,
                Map.of(),
                5000L,
                false,
                null
        );
        StepResult result = StepResult.succeeded(command, Map.of("done", true));

        publisher.publish(result);

        StepResult received = this.received.poll(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.stepExecutionId()).isEqualTo(result.stepExecutionId());
        assertThat(received.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(received.tenantId()).isEqualTo("tenant-integration-test");
    }

    @Test
    void publishSync_blocksUntilAcknowledged() throws Exception {
        StepCommand command = new StepCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "tenant-sync-test",
                "sync_step",
                "DELAY",
                1,
                Map.of("delay_ms", 1000),
                5000L,
                false,
                null
        );
        StepResult result = StepResult.delayRequested(command);

        publisher.publishSync(result); // must not throw

        StepResult received = this.received.poll(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.outcome()).isEqualTo(StepOutcome.DELAY_REQUESTED);
    }
}
```

### Step 5.3 — Verify

```bash
cd worker-service && mvn clean test -Dtest=StepResultPublisherIntegrationTest
```

**Git commit:** `feat(worker): add step result publisher — publishes to workflow.steps.result keyed by tenantId`

---

## Task 6: Graceful Shutdown

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/lifecycle/GracefulShutdownHandler.java`

### Step 6.1 — `worker-service/src/main/java/com/atlas/worker/lifecycle/GracefulShutdownHandler.java`

```java
package com.atlas.worker.lifecycle;

import com.atlas.worker.lease.LeaseManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates graceful shutdown on SIGTERM.
 *
 * Shutdown sequence:
 *   1. Stop all Kafka listener containers — no new messages accepted
 *   2. Wait up to drainTimeoutSeconds for in-flight steps to complete
 *   3. Log warning for any steps that did not drain in time
 *
 * Lease release and result publishing happen inside the consumer thread
 * (StepCommandConsumer) — this handler only controls the outer listener lifecycle.
 */
@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final KafkaListenerEndpointRegistry kafkaRegistry;
    private final int drainTimeoutSeconds;

    /**
     * Latch for tracking in-flight step executions.
     * Consumers call acquire() before execution and release() after.
     * Shutdown waits on this latch.
     */
    private final InFlightTracker inFlightTracker;

    public GracefulShutdownHandler(
            KafkaListenerEndpointRegistry kafkaRegistry,
            InFlightTracker inFlightTracker,
            @Value("${atlas.worker.drain-timeout-seconds:30}") int drainTimeoutSeconds) {
        this.kafkaRegistry = kafkaRegistry;
        this.inFlightTracker = inFlightTracker;
        this.drainTimeoutSeconds = drainTimeoutSeconds;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Graceful shutdown initiated — stopping Kafka consumers");

        // Step 1: Stop all Kafka listeners (no new messages)
        for (MessageListenerContainer container : kafkaRegistry.getListenerContainers()) {
            container.stop();
            log.info("Stopped Kafka listener container: {}", container.getListenerId());
        }

        // Step 2: Wait for in-flight steps to drain
        int inFlight = inFlightTracker.currentCount();
        if (inFlight > 0) {
            log.info("Waiting up to {}s for {} in-flight steps to complete", drainTimeoutSeconds, inFlight);
            boolean drained = inFlightTracker.awaitDrain(drainTimeoutSeconds, TimeUnit.SECONDS);
            if (drained) {
                log.info("All in-flight steps completed — shutdown clean");
            } else {
                log.warn("Drain timeout exceeded — {} steps may not have completed cleanly",
                        inFlightTracker.currentCount());
            }
        } else {
            log.info("No in-flight steps — shutdown immediate");
        }
    }
}
```

### Step 6.2 — `worker-service/src/main/java/com/atlas/worker/lifecycle/InFlightTracker.java`

```java
package com.atlas.worker.lifecycle;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the number of step executions currently in progress.
 * StepCommandConsumer increments on acquire and decrements on release.
 * GracefulShutdownHandler waits until count reaches zero.
 */
@Component
public class InFlightTracker {

    private final AtomicInteger count = new AtomicInteger(0);
    private final Object lock = new Object();

    public void acquire() {
        count.incrementAndGet();
    }

    public void release() {
        int remaining = count.decrementAndGet();
        if (remaining == 0) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public int currentCount() {
        return count.get();
    }

    public boolean awaitDrain(long timeout, TimeUnit unit) {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while (count.get() > 0) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
}
```

### Step 6.3 — Update `StepCommandConsumer` to use `InFlightTracker`

Add `InFlightTracker` injection to `StepCommandConsumer` and wrap execution in acquire/release:

```java
// In StepCommandConsumer constructor, add:
private final InFlightTracker inFlightTracker;

// Updated constructor parameter:
public StepCommandConsumer(
        LeaseManager leaseManager,
        StepExecutorRegistry executorRegistry,
        StepResultPublisher resultPublisher,
        InFlightTracker inFlightTracker,
        MeterRegistry meterRegistry,
        @Value("${atlas.worker.id}") String workerId,
        @Value("${atlas.worker.lease-timeout-seconds:30}") int leaseTimeoutSeconds) {
    // ... existing assignments ...
    this.inFlightTracker = inFlightTracker;
}

// In consume(), after leaseAcquired check, wrap execution:
inFlightTracker.acquire();
try {
    // ... heartbeat, execute, publish, release lease ...
} finally {
    inFlightTracker.release();
}
```

### Step 6.4 — Verify

```bash
cd worker-service && mvn clean test
```

**Git commit:** `feat(worker): add graceful shutdown with configurable drain timeout and in-flight step tracking`

---

## Task 7: Health Indicators and Metrics

**Files to create:**
- `worker-service/src/main/java/com/atlas/worker/health/KafkaHealthIndicator.java`
- `worker-service/src/main/java/com/atlas/worker/health/RedisHealthIndicator.java`
- `worker-service/src/test/java/com/atlas/worker/health/HealthIndicatorTest.java`

### Step 7.1 — `worker-service/src/main/java/com/atlas/worker/health/KafkaHealthIndicator.java`

```java
package com.atlas.worker.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ListTopicsResult topics = adminClient.listTopics();
            Set<String> topicNames = topics.names().get(3, TimeUnit.SECONDS);
            return Health.up()
                    .withDetail("topics", topicNames.size())
                    .withDetail("execute_topic_present",
                            topicNames.contains("workflow.steps.execute"))
                    .withDetail("result_topic_present",
                            topicNames.contains("workflow.steps.result"))
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
```

### Step 7.2 — `worker-service/src/main/java/com/atlas/worker/health/RedisHealthIndicator.java`

```java
package com.atlas.worker.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;

    public RedisHealthIndicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            if ("PONG".equals(pong)) {
                return Health.up()
                        .withDetail("ping", "PONG")
                        .build();
            }
            return Health.down()
                    .withDetail("ping", pong)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
```

### Step 7.3 — `worker-service/src/test/java/com/atlas/worker/health/HealthIndicatorTest.java`

```java
package com.atlas.worker.health;

import com.atlas.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HealthIndicatorTest {

    @Autowired
    private KafkaHealthIndicator kafkaHealthIndicator;

    @Autowired
    private RedisHealthIndicator redisHealthIndicator;

    @Test
    void kafkaHealthIndicator_isUp_whenKafkaReachable() {
        Health health = kafkaHealthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("topics");
    }

    @Test
    void redisHealthIndicator_isUp_whenRedisReachable() {
        Health health = redisHealthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
    }
}
```

### Step 7.4 — Metrics wiring summary

All metrics are registered in `StepCommandConsumer` using `MeterRegistry`. The following metrics are produced automatically once the consumer is running:

| Metric | Type | Registered In |
|--------|------|--------------|
| `atlas.worker.lease.acquired` | Counter | `StepCommandConsumer` |
| `atlas.worker.lease.conflict` | Counter | `StepCommandConsumer` |
| `atlas.worker.step.success` | Counter | `StepCommandConsumer` |
| `atlas.worker.step.failure` | Counter | `StepCommandConsumer` |
| `atlas.worker.step.duration` | Timer | `StepCommandConsumer` |

These are scraped by Prometheus at `/actuator/prometheus` and displayed on the **Worker Execution** Grafana dashboard (provisioned in `infra/grafana/`).

### Step 7.5 — Full test suite run

```bash
cd worker-service && mvn clean verify
```

**Git commit:** `feat(worker): add Kafka and Redis health indicators, metrics wiring for Prometheus/Grafana`

---

## Summary

| Task | Description | Files | Tests |
|------|-------------|-------|-------|
| 1 | Scaffolding | 9 files | 1 test (context loads) |
| 2 | Redis Lease Manager | 2 files | 7 assertions in 6 test methods |
| 3 | Step Executors | 11 files | 5 test classes, 17 test methods |
| 4 | Step Command Consumer | 2 files | 2 integration tests |
| 5 | Result Publisher | 2 files | 2 integration tests |
| 6 | Graceful Shutdown | 3 files | Full suite verification |
| 7 | Health + Metrics | 3 files | 2 tests |

**Total:** 7 tasks, 32 steps, 32 files, 30+ test methods

**Verification command for the full module:**

```bash
cd worker-service && mvn clean verify
```

**Service startup command:**

```bash
cd worker-service && mvn spring-boot:run
```

**Key design decisions:**
- No database of any kind — Redis leases are ephemeral, all durable state is in Workflow Service
- Lua scripts for atomic lease release and extend — prevents race conditions at the Redis level
- Heartbeat uses virtual thread executor (`Thread.ofVirtual().factory()`) — lightweight and aligned with Java 25
- `InFlightTracker` uses `AtomicInteger` + `synchronized` wait/notify rather than `CountDownLatch` to support multiple sequential drains across the lifetime of the JVM process
- Failure injection in `InternalCommandExecutor` is controlled via input keys, not environment flags — demo workflows can declare failure scenarios declaratively in their step definitions
