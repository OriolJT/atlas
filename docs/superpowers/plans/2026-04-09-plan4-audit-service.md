# Plan 4: Audit Service & Observability

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the Audit Service — an append-only event store that consumes audit events from Kafka, persists them with idempotent inserts, and exposes a filtered, paginated query API. Plus provision Grafana dashboards and Prometheus alerting rules.

**Architecture:** Spring Boot 4.0.5 service (port 8084) with PostgreSQL (audit schema) and Kafka consumer. Append-only table with idempotent INSERT ON CONFLICT DO NOTHING. Cursor-based pagination on (occurred_at, audit_event_id).

**Tech Stack:** Java 25, Spring Boot 4.0.5, Spring Data JPA, Spring Kafka, Flyway, PostgreSQL, JUnit 5, Testcontainers 2.0

**Depends on:** Plan 1 (common module, Docker Compose)

**Produces:** Working Audit Service consuming events + query API. Grafana dashboard JSON files and Prometheus alert rules.

---

## Task 1: Audit Service Scaffolding

**Files to create:**
- `audit-service/pom.xml`
- `audit-service/src/main/java/com/atlas/audit/AuditServiceApplication.java`
- `audit-service/src/main/resources/application.yml`
- `audit-service/src/main/java/com/atlas/audit/config/SecurityConfig.java`
- `audit-service/src/main/java/com/atlas/audit/config/JwtConfig.java`
- `audit-service/src/main/java/com/atlas/audit/security/JwtAuthenticationFilter.java`
- `audit-service/src/main/java/com/atlas/audit/security/TenantContext.java`
- `audit-service/src/main/resources/db/migration/V001__create_audit_schema.sql`
- `audit-service/src/test/java/com/atlas/audit/TestcontainersConfiguration.java`
- `audit-service/src/test/java/com/atlas/audit/AuditServiceApplicationTests.java`

### Step 1.1 — `audit-service/pom.xml`

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

    <artifactId>atlas-audit-service</artifactId>
    <name>Atlas Audit Service</name>
    <description>Append-only audit event store: consumes from Kafka, persists to PostgreSQL, exposes query API</description>

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
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

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

        <!-- NO Redis — audit service does not use it -->

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
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
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

### Step 1.2 — `audit-service/src/main/java/com/atlas/audit/AuditServiceApplication.java`

```java
package com.atlas.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
```

### Step 1.3 — `audit-service/src/main/resources/application.yml`

```yaml
server:
  port: 8084

spring:
  application:
    name: audit-service

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:atlas}
    username: ${DB_USER:atlas}
    password: ${DB_PASSWORD:atlas}
    hikari:
      schema: audit

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: audit
        dialect: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    schemas: audit
    default-schema: audit
    locations: classpath:db/migration

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: audit-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.atlas.*"
        spring.json.use.type.headers: false
    listener:
      concurrency: 4

atlas:
  jwt:
    secret: ${ATLAS_JWT_SECRET:change-me-in-production-must-be-at-least-32-chars}

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
      service: audit-service

logging:
  pattern:
    console: '{"timestamp":"%d{yyyy-MM-dd HH:mm:ss.SSS}","level":"%level","service":"audit-service","traceId":"%X{traceId}","correlationId":"%X{correlationId}","tenantId":"%X{tenantId}","message":"%message"}%n'
```

### Step 1.4 — `audit-service/src/main/java/com/atlas/audit/config/SecurityConfig.java`

```java
package com.atlas.audit.config;

import com.atlas.audit.security.JwtAuthenticationFilter;
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

### Step 1.5 — `audit-service/src/main/java/com/atlas/audit/config/JwtConfig.java`

```java
package com.atlas.audit.config;

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

### Step 1.6 — `audit-service/src/main/java/com/atlas/audit/security/TenantContext.java`

```java
package com.atlas.audit.security;

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

### Step 1.7 — `audit-service/src/main/java/com/atlas/audit/security/JwtAuthenticationFilter.java`

```java
package com.atlas.audit.security;

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

### Step 1.8 — `audit-service/src/main/resources/db/migration/V001__create_audit_schema.sql`

```sql
CREATE SCHEMA IF NOT EXISTS audit;
```

### Step 1.9 — `audit-service/src/test/java/com/atlas/audit/TestcontainersConfiguration.java`

```java
package com.atlas.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("atlas_test")
                .withUsername("atlas_test")
                .withPassword("atlas_test");
    }

    @Bean
    @ServiceConnection
    public ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");
    }
}
```

### Step 1.10 — `audit-service/src/test/java/com/atlas/audit/AuditServiceApplicationTests.java`

```java
package com.atlas.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

---

## Task 2: Audit Event Domain

**Files to create:**
- `audit-service/src/main/resources/db/migration/V002__create_audit_events_table.sql`
- `audit-service/src/main/java/com/atlas/audit/domain/AuditEvent.java`
- `audit-service/src/main/java/com/atlas/audit/repository/AuditEventRepository.java`

### Step 2.1 — `audit-service/src/main/resources/db/migration/V002__create_audit_events_table.sql`

```sql
CREATE TABLE audit.audit_events (
    audit_event_id  UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    actor_type      VARCHAR(50) NOT NULL,
    actor_id        UUID,
    event_type      VARCHAR(100) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    resource_id     UUID,
    payload         JSONB       NOT NULL DEFAULT '{}',
    correlation_id  UUID,
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_events_tenant
    ON audit.audit_events (tenant_id);

CREATE INDEX idx_audit_events_tenant_occurred
    ON audit.audit_events (tenant_id, occurred_at);

CREATE INDEX idx_audit_events_type
    ON audit.audit_events (tenant_id, event_type);

CREATE INDEX idx_audit_events_resource
    ON audit.audit_events (tenant_id, resource_type, resource_id);
```

### Step 2.2 — `audit-service/src/main/java/com/atlas/audit/domain/AuditEvent.java`

Append-only entity — no `@PreUpdate`, no setters for mutable state, all fields set at creation time only.

```java
package com.atlas.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_events", schema = "audit")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class AuditEvent {

    @Id
    @Column(name = "audit_event_id", updatable = false, nullable = false)
    private UUID auditEventId;

    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "actor_type", updatable = false, nullable = false, length = 50)
    private String actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "event_type", updatable = false, nullable = false, length = 100)
    private String eventType;

    @Column(name = "resource_type", updatable = false, nullable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(UUID auditEventId, UUID tenantId, String actorType, UUID actorId,
                      String eventType, String resourceType, UUID resourceId,
                      Map<String, Object> payload, UUID correlationId, OffsetDateTime occurredAt) {
        this.auditEventId = auditEventId;
        this.tenantId = tenantId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getAuditEventId() { return auditEventId; }
    public UUID getTenantId() { return tenantId; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getEventType() { return eventType; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public Map<String, Object> getPayload() { return payload; }
    public UUID getCorrelationId() { return correlationId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
```

### Step 2.3 — `audit-service/src/main/java/com/atlas/audit/repository/AuditEventRepository.java`

Cursor-based pagination uses keyset on `(occurred_at, audit_event_id)`. The custom query accepts optional filters; nulls are handled with JPQL's `IS NULL OR` pattern.

```java
package com.atlas.audit.repository;

import com.atlas.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Filtered, cursor-based paginated query.
     *
     * Cursor position: rows where (occurred_at, audit_event_id) come strictly after the cursor
     * values (i.e., occurred_at > cursorOccurredAt, OR occurred_at = cursorOccurredAt AND
     * audit_event_id > cursorAuditEventId). When cursor params are null, the query returns from
     * the beginning of the result set.
     *
     * All optional filters (eventType, resourceType, resourceId, actorId, from, to) accept null,
     * in which case the corresponding condition is skipped.
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.tenantId = :tenantId
              AND (:eventType IS NULL     OR e.eventType = :eventType)
              AND (:resourceType IS NULL  OR e.resourceType = :resourceType)
              AND (:resourceId IS NULL    OR e.resourceId = :resourceId)
              AND (:actorId IS NULL       OR e.actorId = :actorId)
              AND (:from IS NULL          OR e.occurredAt >= :from)
              AND (:to IS NULL            OR e.occurredAt <= :to)
              AND (:cursorOccurredAt IS NULL OR
                    e.occurredAt > :cursorOccurredAt OR
                   (e.occurredAt = :cursorOccurredAt AND e.auditEventId > :cursorAuditEventId))
            ORDER BY e.occurredAt ASC, e.auditEventId ASC
            LIMIT :pageSize
            """)
    List<AuditEvent> findFiltered(
            @Param("tenantId")         UUID tenantId,
            @Param("eventType")        String eventType,
            @Param("resourceType")     String resourceType,
            @Param("resourceId")       UUID resourceId,
            @Param("actorId")          UUID actorId,
            @Param("from")             OffsetDateTime from,
            @Param("to")               OffsetDateTime to,
            @Param("cursorOccurredAt") OffsetDateTime cursorOccurredAt,
            @Param("cursorAuditEventId") UUID cursorAuditEventId,
            @Param("pageSize")         int pageSize
    );
}
```

---

## Task 3: Kafka Consumer for Audit Events

**Files to create:**
- `audit-service/src/main/java/com/atlas/audit/consumer/AuditEventConsumer.java`
- `audit-service/src/test/java/com/atlas/audit/consumer/AuditEventConsumerIntegrationTest.java`

### Step 3.1 — `audit-service/src/main/java/com/atlas/audit/consumer/AuditEventConsumer.java`

Idempotent ingestion: duplicate `audit_event_id` values are silently dropped via `INSERT ON CONFLICT DO NOTHING`, modelled in application code by catching `DataIntegrityViolationException`.

```java
package com.atlas.audit.consumer;

import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.repository.AuditEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditEventRepository repository;
    private final MeterRegistry meterRegistry;

    public AuditEventConsumer(AuditEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "audit.events", groupId = "audit-service")
    public void consume(@Payload Map<String, Object> message,
                        @Header(value = "kafka_receivedTopic", required = false) String topic) {
        UUID auditEventId = UUID.fromString((String) message.get("event_id"));
        UUID tenantId     = UUID.fromString((String) message.get("tenant_id"));
        String actorType  = (String) message.getOrDefault("actor_type", "SYSTEM");
        String actorIdRaw = (String) message.get("actor_id");
        UUID actorId      = actorIdRaw != null ? UUID.fromString(actorIdRaw) : null;
        String eventType  = (String) message.get("event_type");
        String resourceType = (String) message.getOrDefault("resource_type", "UNKNOWN");
        String resourceIdRaw = (String) message.get("resource_id");
        UUID resourceId   = resourceIdRaw != null ? UUID.fromString(resourceIdRaw) : null;
        String correlationIdRaw = (String) message.get("correlation_id");
        UUID correlationId = correlationIdRaw != null ? UUID.fromString(correlationIdRaw) : null;
        OffsetDateTime occurredAt = OffsetDateTime.parse((String) message.get("occurred_at"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = message.containsKey("payload")
                ? (Map<String, Object>) message.get("payload")
                : Map.of();

        AuditEvent event = new AuditEvent(
                auditEventId, tenantId, actorType, actorId,
                eventType, resourceType, resourceId,
                payload, correlationId, occurredAt
        );

        try {
            repository.save(event);
            meterRegistry.counter("atlas.audit.events.ingested",
                    "event_type", eventType,
                    "tenant_id", tenantId.toString()).increment();
            log.debug("Persisted audit event {} type={} tenant={}", auditEventId, eventType, tenantId);
        } catch (DataIntegrityViolationException e) {
            // Duplicate audit_event_id — idempotent, discard silently
            log.debug("Duplicate audit event {} ignored", auditEventId);
        }
    }
}
```

### Step 3.2 — `audit-service/src/test/java/com/atlas/audit/consumer/AuditEventConsumerIntegrationTest.java`

```java
package com.atlas.audit.consumer;

import com.atlas.audit.AuditServiceApplicationTests;
import com.atlas.audit.TestcontainersConfiguration;
import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class AuditEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AuditEventRepository repository;

    @Test
    void publishedAuditEventIsPersistedToDatabase() {
        UUID auditEventId = UUID.randomUUID();
        UUID tenantId     = UUID.randomUUID();

        Map<String, Object> message = Map.of(
                "event_id",     auditEventId.toString(),
                "tenant_id",    tenantId.toString(),
                "actor_type",   "USER",
                "event_type",   "tenant.created",
                "resource_type","TENANT",
                "occurred_at",  OffsetDateTime.now().toString(),
                "payload",      Map.of("name", "acme-corp")
        );

        kafkaTemplate.send("audit.events", tenantId.toString(), message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<AuditEvent> found = repository.findById(auditEventId);
            assertThat(found).isPresent();
            assertThat(found.get().getEventType()).isEqualTo("tenant.created");
            assertThat(found.get().getTenantId()).isEqualTo(tenantId);
        });
    }

    @Test
    void duplicateAuditEventIsIgnoredIdempotently() {
        UUID auditEventId = UUID.randomUUID();
        UUID tenantId     = UUID.randomUUID();

        Map<String, Object> message = Map.of(
                "event_id",     auditEventId.toString(),
                "tenant_id",    tenantId.toString(),
                "actor_type",   "SYSTEM",
                "event_type",   "workflow.execution.started",
                "resource_type","WORKFLOW_EXECUTION",
                "occurred_at",  OffsetDateTime.now().toString(),
                "payload",      Map.of()
        );

        kafkaTemplate.send("audit.events", tenantId.toString(), message);
        kafkaTemplate.send("audit.events", tenantId.toString(), message); // duplicate

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(repository.findById(auditEventId)).isPresent()
        );

        // Only one row must exist — idempotent insert
        assertThat(repository.count()).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(auditEventId)).isPresent();
    }
}
```

---

## Task 4: Query API

**Files to create:**
- `audit-service/src/main/java/com/atlas/audit/dto/AuditEventResponse.java`
- `audit-service/src/main/java/com/atlas/audit/dto/AuditQueryFilters.java`
- `audit-service/src/main/java/com/atlas/audit/dto/CursorPageResponse.java`
- `audit-service/src/main/java/com/atlas/audit/service/AuditQueryService.java`
- `audit-service/src/main/java/com/atlas/audit/controller/AuditEventController.java`
- `audit-service/src/test/java/com/atlas/audit/controller/AuditEventControllerIntegrationTest.java`

### Step 4.1 — `audit-service/src/main/java/com/atlas/audit/dto/AuditEventResponse.java`

```java
package com.atlas.audit.dto;

import com.atlas.audit.domain.AuditEvent;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID auditEventId,
        UUID tenantId,
        String actorType,
        UUID actorId,
        String eventType,
        String resourceType,
        UUID resourceId,
        Map<String, Object> payload,
        UUID correlationId,
        OffsetDateTime occurredAt
) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getAuditEventId(),
                event.getTenantId(),
                event.getActorType(),
                event.getActorId(),
                event.getEventType(),
                event.getResourceType(),
                event.getResourceId(),
                event.getPayload(),
                event.getCorrelationId(),
                event.getOccurredAt()
        );
    }
}
```

### Step 4.2 — `audit-service/src/main/java/com/atlas/audit/dto/AuditQueryFilters.java`

```java
package com.atlas.audit.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditQueryFilters(
        String eventType,
        String resourceType,
        UUID resourceId,
        UUID actorId,
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime cursorOccurredAt,
        UUID cursorAuditEventId,
        int pageSize
) {
    public AuditQueryFilters {
        if (pageSize <= 0) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
    }
}
```

### Step 4.3 — `audit-service/src/main/java/com/atlas/audit/dto/CursorPageResponse.java`

```java
package com.atlas.audit.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        boolean hasMore,
        String nextCursor
) {
}
```

### Step 4.4 — `audit-service/src/main/java/com/atlas/audit/service/AuditQueryService.java`

```java
package com.atlas.audit.service;

import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.dto.AuditEventResponse;
import com.atlas.audit.dto.AuditQueryFilters;
import com.atlas.audit.dto.CursorPageResponse;
import com.atlas.audit.repository.AuditEventRepository;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Timed(value = "atlas.audit.query.latency", description = "Audit event query latency")
    public CursorPageResponse<AuditEventResponse> query(UUID tenantId, AuditQueryFilters filters) {
        // Fetch pageSize + 1 to detect whether there are more pages
        int fetchSize = filters.pageSize() + 1;

        List<AuditEvent> rows = repository.findFiltered(
                tenantId,
                filters.eventType(),
                filters.resourceType(),
                filters.resourceId(),
                filters.actorId(),
                filters.from(),
                filters.to(),
                filters.cursorOccurredAt(),
                filters.cursorAuditEventId(),
                fetchSize
        );

        boolean hasMore = rows.size() > filters.pageSize();
        List<AuditEvent> page = hasMore ? rows.subList(0, filters.pageSize()) : rows;

        String nextCursor = null;
        if (hasMore) {
            AuditEvent last = page.get(page.size() - 1);
            String raw = last.getOccurredAt().toString() + "," + last.getAuditEventId().toString();
            nextCursor = Base64.getUrlEncoder().encodeToString(raw.getBytes());
        }

        List<AuditEventResponse> items = page.stream()
                .map(AuditEventResponse::from)
                .toList();

        return new CursorPageResponse<>(items, hasMore, nextCursor);
    }
}
```

### Step 4.5 — `audit-service/src/main/java/com/atlas/audit/controller/AuditEventController.java`

```java
package com.atlas.audit.controller;

import com.atlas.audit.dto.AuditEventResponse;
import com.atlas.audit.dto.AuditQueryFilters;
import com.atlas.audit.dto.CursorPageResponse;
import com.atlas.audit.security.TenantContext;
import com.atlas.audit.service.AuditQueryService;
import com.atlas.common.web.ErrorResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditQueryService queryService;
    private final TenantContext tenantContext;

    public AuditEventController(AuditQueryService queryService, TenantContext tenantContext) {
        this.queryService = queryService;
        this.tenantContext = tenantContext;
    }

    /**
     * GET /api/v1/audit-events
     *
     * Query Parameters:
     *   event_type     (optional) filter by event type string
     *   resource_type  (optional) filter by resource type
     *   resource_id    (optional) filter by resource UUID
     *   actor_id       (optional) filter by actor UUID
     *   from           (optional) ISO-8601 lower bound for occurred_at
     *   to             (optional) ISO-8601 upper bound for occurred_at
     *   cursor         (optional) base64-encoded next-page cursor from previous response
     *   size           (optional, default 20, max 100) page size
     *
     * Requires audit.read permission in JWT roles.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('audit.read') or hasRole('ADMIN')")
    public ResponseEntity<CursorPageResponse<AuditEventResponse>> queryAuditEvents(
            @RequestParam(required = false) String event_type,
            @RequestParam(required = false) String resource_type,
            @RequestParam(required = false) UUID resource_id,
            @RequestParam(required = false) UUID actor_id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = UUID.fromString(tenantContext.getTenantId());

        OffsetDateTime cursorOccurredAt = null;
        UUID cursorAuditEventId = null;
        if (cursor != null) {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",", 2);
            if (parts.length == 2) {
                cursorOccurredAt = OffsetDateTime.parse(parts[0]);
                cursorAuditEventId = UUID.fromString(parts[1]);
            }
        }

        AuditQueryFilters filters = new AuditQueryFilters(
                event_type, resource_type, resource_id, actor_id,
                from, to,
                cursorOccurredAt, cursorAuditEventId,
                size
        );

        return ResponseEntity.ok(queryService.query(tenantId, filters));
    }
}
```

### Step 4.6 — `audit-service/src/test/java/com/atlas/audit/controller/AuditEventControllerIntegrationTest.java`

```java
package com.atlas.audit.controller;

import com.atlas.audit.TestcontainersConfiguration;
import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuditEventControllerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AuditEventRepository repository;

    WebTestClient client;

    // A valid JWT signed with the test secret. In practice, generate via JwtTokenParser or use
    // a test helper from the common module. Placeholder token shown for illustration.
    static final String TEST_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    static final String OTHER_TENANT_ID = "00000000-0000-0000-0000-000000000002";

    @BeforeEach
    void setup() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        repository.deleteAll();
    }

    @Test
    void queryWithNoFiltersReturnsAllEventsForTenant() {
        UUID tenantId = UUID.fromString(TEST_TENANT_ID);
        insertEvent(tenantId, "tenant.created", "TENANT", null);
        insertEvent(tenantId, "user.created", "USER", null);

        client.get().uri("/api/v1/audit-events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(TEST_TENANT_ID, "audit.read"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.hasMore").isEqualTo(false);
    }

    @Test
    void queryFilteredByEventType() {
        UUID tenantId = UUID.fromString(TEST_TENANT_ID);
        insertEvent(tenantId, "tenant.created", "TENANT", null);
        insertEvent(tenantId, "user.created", "USER", null);

        client.get().uri("/api/v1/audit-events?event_type=tenant.created")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(TEST_TENANT_ID, "audit.read"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].eventType").isEqualTo("tenant.created");
    }

    @Test
    void queryReturnsEmptyResultWhenNoEventsMatch() {
        UUID tenantId = UUID.fromString(TEST_TENANT_ID);
        insertEvent(tenantId, "user.created", "USER", null);

        client.get().uri("/api/v1/audit-events?event_type=workflow.execution.started")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(TEST_TENANT_ID, "audit.read"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.hasMore").isEqualTo(false);
    }

    @Test
    void paginationCursorIsReturnedWhenMorePagesExist() {
        UUID tenantId = UUID.fromString(TEST_TENANT_ID);
        for (int i = 0; i < 5; i++) {
            insertEvent(tenantId, "user.created", "USER", null);
        }

        client.get().uri("/api/v1/audit-events?size=3")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(TEST_TENANT_ID, "audit.read"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(3)
                .jsonPath("$.hasMore").isEqualTo(true)
                .jsonPath("$.nextCursor").isNotEmpty();
    }

    @Test
    void queryWithoutAuthIsRejected() {
        client.get().uri("/api/v1/audit-events")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- helpers ---

    private void insertEvent(UUID tenantId, String eventType, String resourceType, UUID resourceId) {
        repository.save(new AuditEvent(
                UUID.randomUUID(), tenantId,
                "SYSTEM", null,
                eventType, resourceType, resourceId,
                Map.of(), null,
                OffsetDateTime.now()
        ));
    }

    /**
     * Generate a minimal test JWT. Replace with a proper test token helper from common module.
     * The token must be signed with the secret configured in application.yml for tests.
     */
    private String tokenFor(String tenantId, String... roles) {
        // TODO: use JwtTokenParser or a dedicated TestJwtHelper.buildToken(tenantId, roles)
        // from the common module test utilities.
        throw new UnsupportedOperationException(
                "Implement with TestJwtHelper from common module");
    }
}
```

> **Implementation note for Task 4 tests:** The `tokenFor` helper must be implemented using a test JWT builder from the common module (e.g., `TestJwtHelper`). If it does not yet exist, create it in `atlas-common/src/test/java/com/atlas/common/security/TestJwtHelper.java` using JJWT to sign a token with the test secret and the provided `tenantId` and `roles` claims.

---

## Task 5: Tenant Scoping

**Files to create:**
- `audit-service/src/main/java/com/atlas/audit/config/HibernateFilterAspect.java`
- `audit-service/src/test/java/com/atlas/audit/tenant/CrossTenantIsolationTest.java`

### Step 5.1 — `audit-service/src/main/java/com/atlas/audit/config/HibernateFilterAspect.java`

This aspect enables the Hibernate `tenantFilter` on every `EntityManager` session so that all JPA queries automatically scope results to the current tenant.

```java
package com.atlas.audit.config;

import com.atlas.audit.security.TenantContext;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class HibernateFilterAspect {

    private final EntityManager entityManager;
    private final TenantContext tenantContext;

    public HibernateFilterAspect(EntityManager entityManager, TenantContext tenantContext) {
        this.entityManager = entityManager;
        this.tenantContext = tenantContext;
    }

    @Around("execution(* com.atlas.audit.repository.*.*(..))")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        String tenantIdStr = tenantContext.getTenantId();
        Session session = entityManager.unwrap(Session.class);

        if (tenantIdStr != null) {
            session.enableFilter("tenantFilter")
                    .setParameter("tenantId", UUID.fromString(tenantIdStr));
        }

        try {
            return joinPoint.proceed();
        } finally {
            session.disableFilter("tenantFilter");
        }
    }
}
```

### Step 5.2 — `audit-service/src/test/java/com/atlas/audit/tenant/CrossTenantIsolationTest.java`

```java
package com.atlas.audit.tenant;

import com.atlas.audit.TestcontainersConfiguration;
import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.dto.AuditQueryFilters;
import com.atlas.audit.dto.CursorPageResponse;
import com.atlas.audit.repository.AuditEventRepository;
import com.atlas.audit.security.TenantContext;
import com.atlas.audit.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrossTenantIsolationTest {

    @Autowired
    AuditEventRepository repository;

    @Autowired
    AuditQueryService queryService;

    @Autowired
    TenantContext tenantContext;

    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        repository.deleteAll();

        repository.save(new AuditEvent(
                UUID.randomUUID(), tenantA, "USER", null,
                "user.created", "USER", null,
                Map.of(), null, OffsetDateTime.now()
        ));
        repository.save(new AuditEvent(
                UUID.randomUUID(), tenantB, "USER", null,
                "tenant.created", "TENANT", null,
                Map.of(), null, OffsetDateTime.now()
        ));
    }

    @Test
    void tenantACannotSeeTeantBEvents() {
        tenantContext.setTenantId(tenantA.toString());

        CursorPageResponse<?> result = queryService.query(tenantA,
                new AuditQueryFilters(null, null, null, null, null, null, null, null, 20));

        assertThat(result.items()).hasSize(1);
        // The single item must belong to tenantA
    }

    @Test
    void tenantBCannotSeeTenantAEvents() {
        tenantContext.setTenantId(tenantB.toString());

        CursorPageResponse<?> result = queryService.query(tenantB,
                new AuditQueryFilters(null, null, null, null, null, null, null, null, 20));

        assertThat(result.items()).hasSize(1);
    }
}
```

---

## Task 6: Grafana Dashboards and Prometheus Alerts

**Files to create:**
- `infra/grafana/provisioning/dashboards.yml`
- `infra/grafana/dashboards/platform-health.json`
- `infra/grafana/dashboards/workflow-executions.json`
- `infra/grafana/dashboards/failures-retries.json`
- `infra/grafana/dashboards/tenant-activity.json`
- `infra/prometheus/alerts.yml`
- Update `infra/docker-compose.yml` to mount Grafana dashboard provisioning

No tests — these are infrastructure configuration files.

### Step 6.1 — `infra/grafana/provisioning/dashboards.yml`

```yaml
apiVersion: 1

providers:
  - name: Atlas Dashboards
    orgId: 1
    folder: Atlas
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    allowUiUpdates: true
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

### Step 6.2 — `infra/grafana/dashboards/platform-health.json`

```json
{
  "id": null,
  "uid": "atlas-platform-health",
  "title": "Atlas — Platform Health",
  "tags": ["atlas"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-1h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "stat",
      "title": "Services Up",
      "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },
      "targets": [
        {
          "expr": "count(up{job=~\"atlas-.*\"} == 1)",
          "legendFormat": "Up"
        }
      ],
      "options": { "colorMode": "background", "graphMode": "none" },
      "fieldConfig": {
        "defaults": {
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "red", "value": null },
              { "color": "green", "value": 4 }
            ]
          }
        }
      }
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "HTTP Error Rate (5xx)",
      "gridPos": { "x": 6, "y": 0, "w": 9, "h": 8 },
      "targets": [
        {
          "expr": "sum by (service) (rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Kafka Consumer Lag",
      "gridPos": { "x": 15, "y": 0, "w": 9, "h": 8 },
      "targets": [
        {
          "expr": "sum by (service) (kafka_consumer_fetch_manager_records_lag{job=~\"atlas-.*\"})",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "JVM Heap Used",
      "gridPos": { "x": 0, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "jvm_memory_used_bytes{area=\"heap\",job=~\"atlas-.*\"}",
          "legendFormat": "{{job}}"
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "DB Connection Pool Active",
      "gridPos": { "x": 12, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "hikaricp_connections_active{job=~\"atlas-.*\"}",
          "legendFormat": "{{job}}"
        }
      ]
    }
  ]
}
```

### Step 6.3 — `infra/grafana/dashboards/workflow-executions.json`

```json
{
  "id": null,
  "uid": "atlas-workflow-executions",
  "title": "Atlas — Workflow Executions",
  "tags": ["atlas", "workflow"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-1h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Executions Started / Completed / Failed",
      "gridPos": { "x": 0, "y": 0, "w": 24, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_workflow_execution_started_total[5m])",
          "legendFormat": "Started"
        },
        {
          "expr": "rate(atlas_workflow_execution_completed_total[5m])",
          "legendFormat": "Completed"
        },
        {
          "expr": "rate(atlas_workflow_execution_failed_total[5m])",
          "legendFormat": "Failed"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "Step Duration (p50 / p95 / p99)",
      "gridPos": { "x": 0, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "histogram_quantile(0.50, sum(rate(atlas_workflow_step_duration_seconds_bucket[5m])) by (le))",
          "legendFormat": "p50"
        },
        {
          "expr": "histogram_quantile(0.95, sum(rate(atlas_workflow_step_duration_seconds_bucket[5m])) by (le))",
          "legendFormat": "p95"
        },
        {
          "expr": "histogram_quantile(0.99, sum(rate(atlas_workflow_step_duration_seconds_bucket[5m])) by (le))",
          "legendFormat": "p99"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Compensation Rate",
      "gridPos": { "x": 12, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_workflow_execution_compensating_total[5m])",
          "legendFormat": "Compensating"
        }
      ]
    }
  ]
}
```

### Step 6.4 — `infra/grafana/dashboards/failures-retries.json`

```json
{
  "id": null,
  "uid": "atlas-failures-retries",
  "title": "Atlas — Failures & Retries",
  "tags": ["atlas", "reliability"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-1h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Step Retry Rate",
      "gridPos": { "x": 0, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_workflow_step_retries_total[5m])",
          "legendFormat": "Retries/s"
        }
      ]
    },
    {
      "id": 2,
      "type": "gauge",
      "title": "Dead-Letter Queue Depth",
      "gridPos": { "x": 12, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "atlas_workflow_deadletter_count",
          "legendFormat": "DLQ depth"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 10 },
              { "color": "red", "value": 50 }
            ]
          }
        }
      }
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Worker Step Failures",
      "gridPos": { "x": 0, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_worker_step_failure_total[5m])",
          "legendFormat": "Failures/s"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "Outbox Unpublished Row Count",
      "gridPos": { "x": 12, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "atlas_outbox_unpublished_rows",
          "legendFormat": "Unpublished rows"
        }
      ]
    }
  ]
}
```

### Step 6.5 — `infra/grafana/dashboards/tenant-activity.json`

```json
{
  "id": null,
  "uid": "atlas-tenant-activity",
  "title": "Atlas — Tenant Activity",
  "tags": ["atlas", "tenant"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": { "from": "now-1h", "to": "now" },
  "templating": {
    "list": [
      {
        "name": "tenant_id",
        "label": "Tenant",
        "type": "query",
        "datasource": "Prometheus",
        "query": "label_values(atlas_workflow_execution_started_total, tenant_id)",
        "refresh": 2,
        "includeAll": true,
        "allValue": ".*"
      }
    ]
  },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Executions per Tenant",
      "gridPos": { "x": 0, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_workflow_execution_started_total{tenant_id=~\"$tenant_id\"}[5m])",
          "legendFormat": "{{tenant_id}}"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "API Request Rate per Tenant",
      "gridPos": { "x": 12, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(http_server_requests_seconds_count{tenant_id=~\"$tenant_id\"}[5m])",
          "legendFormat": "{{tenant_id}}"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Auth Failure Rate per Tenant",
      "gridPos": { "x": 0, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_auth_login_failure_total{tenant_id=~\"$tenant_id\"}[5m])",
          "legendFormat": "{{tenant_id}}"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "Audit Events Ingested per Tenant",
      "gridPos": { "x": 12, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(atlas_audit_events_ingested_total{tenant_id=~\"$tenant_id\"}[5m])",
          "legendFormat": "{{tenant_id}}"
        }
      ]
    }
  ]
}
```

### Step 6.6 — `infra/prometheus/alerts.yml`

```yaml
groups:
  - name: atlas-alerts
    rules:

      # 1. Kafka consumer lag above threshold for 5 minutes
      - alert: KafkaConsumerLagHigh
        expr: >
          sum by (service) (
            kafka_consumer_fetch_manager_records_lag{job=~"atlas-.*"}
          ) > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka consumer lag is high for {{ $labels.service }}"
          description: >
            Consumer lag for {{ $labels.service }} has been above 1000 for more than 5 minutes.
            Current value: {{ $value }}.

      # 2. Dead-letter queue depth increasing
      - alert: DeadLetterQueueGrowing
        expr: >
          increase(atlas_workflow_deadletter_count[10m]) > 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Dead-letter queue depth is increasing"
          description: >
            The dead-letter queue has grown in the last 10 minutes.
            Current depth: {{ $value }}.

      # 3. Execution failure rate exceeds 10% over 5 minutes
      - alert: ExecutionFailureRateHigh
        expr: >
          (
            rate(atlas_workflow_execution_failed_total[5m])
            /
            (rate(atlas_workflow_execution_started_total[5m]) + 0.001)
          ) > 0.10
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Workflow execution failure rate above 10%"
          description: >
            More than 10% of workflow executions are failing over the last 5 minutes.
            Current rate: {{ $value | humanizePercentage }}.

      # 4. Service health endpoint unreachable for 30 seconds
      - alert: ServiceHealthCheckDown
        expr: up{job=~"atlas-.*"} == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Atlas service {{ $labels.job }} is down"
          description: >
            The health check endpoint for {{ $labels.job }} ({{ $labels.instance }})
            has been unreachable for at least 30 seconds.

      # 5. Outbox poller stuck — unpublished rows growing
      - alert: OutboxPollerStuck
        expr: >
          increase(atlas_outbox_unpublished_rows[10m]) > 50
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Outbox unpublished rows are growing — poller may be stuck"
          description: >
            The number of unpublished outbox rows has increased by more than 50
            in the last 10 minutes. The outbox poller may be stuck or Kafka may be down.
            Current count: {{ $value }}.
```

### Step 6.7 — Update `infra/docker-compose.yml`

Locate the `grafana` service definition in `infra/docker-compose.yml` and add the following volume mounts so Grafana auto-provisions the dashboards on startup. The existing volumes block should be extended:

```yaml
# In the grafana service, add these volume mounts:
volumes:
  - grafana-data:/var/lib/grafana
  - ./grafana/provisioning:/etc/grafana/provisioning
  - ./grafana/dashboards:/var/lib/grafana/dashboards
```

Also ensure a `datasource` provisioning file exists at `infra/grafana/provisioning/datasources/prometheus.yml` so Grafana connects to Prometheus automatically:

```yaml
# infra/grafana/provisioning/datasources/prometheus.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      httpMethod: POST
      timeInterval: "15s"
```

---

## Summary

| Task | Files | Steps |
|------|-------|-------|
| Task 1: Scaffolding | 10 | 10 |
| Task 2: Audit Event domain | 3 | 3 |
| Task 3: Kafka consumer | 2 | 2 |
| Task 4: Query API | 6 | 6 |
| Task 5: Tenant scoping | 2 | 2 |
| Task 6: Grafana & Prometheus | 7 (+1 update) | 8 |
| **Total** | **30** | **31** |

**Key implementation notes:**
- No outbox, no Redis — pure consumer + query service
- Idempotent ingestion: catch `DataIntegrityViolationException` wrapping PK conflict; the underlying SQL uses `INSERT ... ON CONFLICT DO NOTHING`
- Append-only: `AuditEvent` has no setters post-construction, all columns are `updatable = false`
- Cursor encoding: base64url of `occurred_at ISO-8601 + "," + audit_event_id` — stateless, no server-side cursor storage
- Tenant isolation via Hibernate `@Filter` applied by `HibernateFilterAspect` on all repository calls
- Metrics: `atlas.audit.events.ingested` (counter, tagged by `event_type` and `tenant_id`) and `atlas.audit.query.latency` (timer via `@Timed`)
- Error code `ATLAS-AUDIT-001` returned for invalid query parameters (wire up in a `@ControllerAdvice` that validates the filter parameters)
