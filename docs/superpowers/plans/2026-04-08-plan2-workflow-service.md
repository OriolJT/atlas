# Plan 2: Workflow Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the Workflow Service — the orchestration brain that manages workflow definitions, drives execution via state machine, coordinates retries and compensation, and publishes events through the outbox pattern.

**Architecture:** Spring Boot 4.0.5 service (port 8082) with PostgreSQL (workflow schema), Kafka for step command publishing and result consumption, Redis for delay scheduling. Orchestration-first pattern where the Workflow Service owns all execution state.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Spring Data JPA, Spring Kafka, Flyway, PostgreSQL, Redis, JUnit 5, Testcontainers 2.0

**Depends on:** Plan 1 (common module, Docker Compose infrastructure)

**Produces:** Working Workflow Service that can register definitions, start executions, process step results from workers, handle retries/compensation/dead-letter, and expose timeline/signal/cancel APIs.

---

## Task 1: Workflow Service Scaffolding

**Files to create:**
- `workflow-service/pom.xml`
- `workflow-service/src/main/java/com/atlas/workflow/WorkflowServiceApplication.java`
- `workflow-service/src/main/resources/application.yml`
- `workflow-service/src/main/java/com/atlas/workflow/config/SecurityConfig.java`
- `workflow-service/src/main/java/com/atlas/workflow/security/JwtAuthenticationFilter.java`
- `workflow-service/src/main/resources/db/migration/V001__create_schema.sql`
- `workflow-service/src/test/java/com/atlas/workflow/TestcontainersConfiguration.java`
- `workflow-service/src/test/java/com/atlas/workflow/WorkflowServiceApplicationTests.java`

**Files to modify:**
- `pom.xml` (root) — add `workflow-service` module

### Step 1.1 — Add workflow-service module to root pom.xml

In the root `pom.xml`, add `<module>workflow-service</module>` alongside the existing modules (`common`, `identity-service`, etc.).

### Step 1.2 — Create workflow-service/pom.xml

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

    <artifactId>atlas-workflow-service</artifactId>
    <name>Atlas Workflow Service</name>
    <description>Workflow definition management, execution lifecycle, state machine, compensation orchestration</description>

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

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- AOP / AspectJ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aspectj</artifactId>
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

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-restclient</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-resttestclient</artifactId>
            <scope>test</scope>
        </dependency>
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
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-redis</artifactId>
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

### Step 1.3 — Create WorkflowServiceApplication.java

```java
package com.atlas.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkflowServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
```

### Step 1.4 — Create application.yml

```yaml
server:
  port: ${ATLAS_WORKFLOW_PORT:8082}

spring:
  application:
    name: workflow-service
  datasource:
    url: jdbc:postgresql://${ATLAS_DB_HOST:localhost}:${ATLAS_DB_PORT:5432}/${ATLAS_DB_NAME:atlas}?currentSchema=workflow
    username: ${ATLAS_DB_USER:atlas}
    password: ${ATLAS_DB_PASSWORD:atlas}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: workflow
        format_sql: true
    open-in-view: false
  flyway:
    schemas: workflow
    default-schema: workflow
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${ATLAS_KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
    consumer:
      group-id: workflow-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
      properties:
        spring.json.trusted.packages: "com.atlas.*"
  data:
    redis:
      host: ${ATLAS_REDIS_HOST:localhost}
      port: ${ATLAS_REDIS_PORT:6379}

atlas:
  jwt:
    secret: ${ATLAS_JWT_SECRET:atlas-jwt-secret-key-that-is-at-least-256-bits-long-for-hs256}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: workflow-service
```

### Step 1.5 — Create V001__create_schema.sql

```sql
CREATE SCHEMA IF NOT EXISTS workflow;
```

### Step 1.6 — Create SecurityConfig.java

```java
package com.atlas.workflow.config;

import com.atlas.workflow.security.JwtAuthenticationFilter;
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

### Step 1.7 — Create JwtAuthenticationFilter.java

```java
package com.atlas.workflow.security;

import com.atlas.common.security.AuthenticatedPrincipal;
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

    public JwtAuthenticationFilter(JwtTokenParser jwtTokenParser) {
        this.jwtTokenParser = jwtTokenParser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            AuthenticatedPrincipal principal = jwtTokenParser.parse(token);

            if (principal != null) {
                var authorities = principal.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### Step 1.8 — Create TestcontainersConfiguration.java

```java
package com.atlas.workflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.containers.GenericContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("atlas_test")
                .withUsername("atlas_test")
                .withPassword("atlas_test");
    }

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

### Step 1.9 — Create WorkflowServiceApplicationTests.java

```java
package com.atlas.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkflowServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

### Step 1.10 — Verify compile

```bash
mvn -pl workflow-service -am clean compile -q
mvn -pl workflow-service test -Dtest=WorkflowServiceApplicationTests
```

**Git commit message:** `feat(workflow-service): scaffold module with security, flyway schema, testcontainers`

---

## Task 2: Workflow Definition Domain and API

**Files to create:**
- `workflow-service/src/main/resources/db/migration/V002__create_workflow_definitions.sql`
- `workflow-service/src/main/java/com/atlas/workflow/domain/WorkflowDefinition.java`
- `workflow-service/src/main/java/com/atlas/workflow/domain/DefinitionStatus.java`
- `workflow-service/src/main/java/com/atlas/workflow/repository/WorkflowDefinitionRepository.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/WorkflowDefinitionService.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/CreateDefinitionRequest.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/DefinitionResponse.java`
- `workflow-service/src/main/java/com/atlas/workflow/controller/WorkflowDefinitionController.java`
- `workflow-service/src/test/java/com/atlas/workflow/controller/WorkflowDefinitionControllerIT.java`

### Step 2.1 — Create V002__create_workflow_definitions.sql

```sql
CREATE TABLE workflow.workflow_definitions (
    definition_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    name             VARCHAR(255) NOT NULL,
    version          INT          NOT NULL DEFAULT 1,
    status           VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    steps_json       JSONB        NOT NULL,
    compensations_json JSONB      NOT NULL DEFAULT '{}',
    trigger_type     VARCHAR(50)  NOT NULL DEFAULT 'API',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    CONSTRAINT uq_definition_name_version_tenant UNIQUE (tenant_id, name, version)
);

CREATE INDEX idx_workflow_definitions_tenant ON workflow.workflow_definitions (tenant_id);
CREATE INDEX idx_workflow_definitions_status  ON workflow.workflow_definitions (tenant_id, status);
```

### Step 2.2 — Create DefinitionStatus.java

```java
package com.atlas.workflow.domain;

public enum DefinitionStatus {
    DRAFT,
    PUBLISHED
}
```

### Step 2.3 — Create WorkflowDefinition.java

```java
package com.atlas.workflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions", schema = "workflow")
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "definition_id")
    private UUID definitionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DefinitionStatus status = DefinitionStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps_json", nullable = false, columnDefinition = "jsonb")
    private Object stepsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "compensations_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> compensationsJson = Map.of();

    @Column(name = "trigger_type", nullable = false)
    private String triggerType = "API";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkflowDefinition() {}

    public static WorkflowDefinition create(UUID tenantId, String name, int version,
                                             Object stepsJson, Map<String, Object> compensationsJson,
                                             String triggerType) {
        var def = new WorkflowDefinition();
        def.tenantId = tenantId;
        def.name = name;
        def.version = version;
        def.stepsJson = stepsJson;
        def.compensationsJson = compensationsJson != null ? compensationsJson : Map.of();
        def.triggerType = triggerType != null ? triggerType : "API";
        def.status = DefinitionStatus.DRAFT;
        def.createdAt = Instant.now();
        return def;
    }

    public void publish() {
        if (this.status != DefinitionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT definitions can be published; current status: " + this.status);
        }
        this.status = DefinitionStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public UUID getDefinitionId() { return definitionId; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public DefinitionStatus getStatus() { return status; }
    public Object getStepsJson() { return stepsJson; }
    public Map<String, Object> getCompensationsJson() { return compensationsJson; }
    public String getTriggerType() { return triggerType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
```

### Step 2.4 — Create WorkflowDefinitionRepository.java

```java
package com.atlas.workflow.repository;

import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.DefinitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    Optional<WorkflowDefinition> findByDefinitionIdAndTenantId(UUID definitionId, UUID tenantId);

    Optional<WorkflowDefinition> findByTenantIdAndNameAndVersion(UUID tenantId, String name, int version);

    boolean existsByTenantIdAndNameAndVersion(UUID tenantId, String name, int version);
}
```

### Step 2.5 — Create DTOs

**CreateDefinitionRequest.java:**

```java
package com.atlas.workflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateDefinitionRequest(
        @NotBlank @Size(max = 255) String name,
        @Min(1) int version,
        @NotNull @Size(min = 1) List<Map<String, Object>> steps,
        Map<String, Object> compensations,
        String triggerType
) {}
```

**DefinitionResponse.java:**

```java
package com.atlas.workflow.dto;

import com.atlas.workflow.domain.WorkflowDefinition;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DefinitionResponse(
        UUID definitionId,
        UUID tenantId,
        String name,
        int version,
        String status,
        Object steps,
        Map<String, Object> compensations,
        String triggerType,
        Instant createdAt,
        Instant publishedAt
) {
    public static DefinitionResponse from(WorkflowDefinition def) {
        return new DefinitionResponse(
                def.getDefinitionId(),
                def.getTenantId(),
                def.getName(),
                def.getVersion(),
                def.getStatus().name(),
                def.getStepsJson(),
                def.getCompensationsJson(),
                def.getTriggerType(),
                def.getCreatedAt(),
                def.getPublishedAt()
        );
    }
}
```

### Step 2.6 — Create WorkflowDefinitionService.java

```java
package com.atlas.workflow.service;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository repository;

    public WorkflowDefinitionService(WorkflowDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WorkflowDefinition create(CreateDefinitionRequest request, UUID tenantId) {
        if (repository.existsByTenantIdAndNameAndVersion(tenantId, request.name(), request.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Definition '" + request.name() + "' v" + request.version() + " already exists");
        }
        var definition = WorkflowDefinition.create(
                tenantId,
                request.name(),
                request.version(),
                request.steps(),
                request.compensations(),
                request.triggerType()
        );
        return repository.save(definition);
    }

    public WorkflowDefinition getById(UUID definitionId, UUID tenantId) {
        return repository.findByDefinitionIdAndTenantId(definitionId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + definitionId));
    }

    @Transactional
    public WorkflowDefinition publish(UUID definitionId, UUID tenantId) {
        WorkflowDefinition definition = getById(definitionId, tenantId);
        definition.publish();
        return repository.save(definition);
    }
}
```

### Step 2.7 — Create WorkflowDefinitionController.java

```java
package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.DefinitionResponse;
import com.atlas.workflow.service.WorkflowDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow-definitions")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionResponse create(
            @Valid @RequestBody CreateDefinitionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return DefinitionResponse.from(service.create(request, principal.tenantId()));
    }

    @GetMapping("/{id}")
    public DefinitionResponse getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return DefinitionResponse.from(service.getById(id, principal.tenantId()));
    }

    @PostMapping("/{id}/publish")
    public DefinitionResponse publish(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return DefinitionResponse.from(service.publish(id, principal.tenantId()));
    }
}
```

### Step 2.8 — Create WorkflowDefinitionControllerIT.java

```java
package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkflowDefinitionControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    private String bearerToken() {
        // Use a test JWT signed with the test secret configured in application-test.yml
        // The token encodes: sub=user-1, tenant_id=<fixed-test-tenant-uuid>, roles=[TENANT_ADMIN]
        return "Bearer test-jwt-token";
    }

    @Test
    void createDefinition_returns201() {
        var request = Map.of(
                "name", "order-fulfillment",
                "version", 1,
                "steps", List.of(Map.of("name", "validate-order", "type", "INTERNAL_COMMAND")),
                "compensations", Map.of(),
                "triggerType", "API"
        );

        var headers = new HttpHeaders();
        headers.setBearerAuth("test-jwt-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("definitionId");
        assertThat(response.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(response.getBody().get("name")).isEqualTo("order-fulfillment");
    }

    @Test
    void publishDefinition_transitions_DRAFT_to_PUBLISHED() {
        // Step 1: create
        var createRequest = Map.of(
                "name", "to-publish",
                "version", 1,
                "steps", List.of(Map.of("name", "step1", "type", "INTERNAL_COMMAND")),
                "compensations", Map.of(),
                "triggerType", "API"
        );

        var headers = new HttpHeaders();
        headers.setBearerAuth("test-jwt-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, headers),
                Map.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String definitionId = (String) createResponse.getBody().get("definitionId");

        // Step 2: publish
        ResponseEntity<Map> publishResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                Map.class
        );

        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishResponse.getBody().get("status")).isEqualTo("PUBLISHED");
        assertThat(publishResponse.getBody().get("publishedAt")).isNotNull();
    }

    @Test
    void publishAlreadyPublished_returns409() {
        // Create and publish a definition, then try to publish again
        var createRequest = Map.of(
                "name", "double-publish",
                "version", 1,
                "steps", List.of(Map.of("name", "step1", "type", "INTERNAL_COMMAND")),
                "compensations", Map.of(),
                "triggerType", "API"
        );

        var headers = new HttpHeaders();
        headers.setBearerAuth("test-jwt-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, headers),
                Map.class
        );
        String definitionId = (String) createResponse.getBody().get("definitionId");

        restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST, new HttpEntity<>(null, headers), Map.class);

        ResponseEntity<Map> secondPublish = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST, new HttpEntity<>(null, headers), Map.class);

        assertThat(secondPublish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getDefinition_unknownId_returns404() {
        var headers = new HttpHeaders();
        headers.setBearerAuth("test-jwt-token");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(null, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

### Step 2.9 — Run tests

```bash
mvn -pl workflow-service test -Dtest=WorkflowDefinitionControllerIT
```

**Git commit message:** `feat(workflow-service): workflow definition domain, DRAFT→PUBLISHED lifecycle, REST API`

---

## Task 3: Workflow Execution Domain and State Machine

**Files to create:**
- `workflow-service/src/main/resources/db/migration/V003__create_workflow_executions.sql`
- `workflow-service/src/main/java/com/atlas/workflow/domain/ExecutionStatus.java`
- `workflow-service/src/main/java/com/atlas/workflow/domain/WorkflowExecution.java`
- `workflow-service/src/main/java/com/atlas/workflow/repository/WorkflowExecutionRepository.java`
- `workflow-service/src/main/java/com/atlas/workflow/statemachine/ExecutionStateMachine.java`
- `workflow-service/src/test/java/com/atlas/workflow/statemachine/ExecutionStateMachineTest.java`

### Step 3.1 — Create V003__create_workflow_executions.sql

```sql
CREATE TABLE workflow.workflow_executions (
    execution_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    definition_id      UUID         NOT NULL REFERENCES workflow.workflow_definitions(definition_id),
    idempotency_key    VARCHAR(255) NOT NULL,
    status             VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    input_json         JSONB        NOT NULL DEFAULT '{}',
    output_json        JSONB,
    error_message      TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    canceled_at        TIMESTAMPTZ,
    timed_out_at       TIMESTAMPTZ,
    correlation_id     VARCHAR(255),
    CONSTRAINT uq_execution_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_workflow_executions_tenant        ON workflow.workflow_executions (tenant_id);
CREATE INDEX idx_workflow_executions_status        ON workflow.workflow_executions (tenant_id, status);
CREATE INDEX idx_workflow_executions_definition    ON workflow.workflow_executions (definition_id);
CREATE INDEX idx_workflow_executions_idempotency   ON workflow.workflow_executions (tenant_id, idempotency_key);
```

### Step 3.2 — Create ExecutionStatus.java

```java
package com.atlas.workflow.domain;

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    CANCELED,
    TIMED_OUT
}
```

### Step 3.3 — Create WorkflowExecution.java

```java
package com.atlas.workflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_executions", schema = "workflow")
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Map<String, Object> inputJson = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private Map<String, Object> outputJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "timed_out_at")
    private Instant timedOutAt;

    @Column(name = "correlation_id")
    private String correlationId;

    protected WorkflowExecution() {}

    public static WorkflowExecution create(UUID tenantId, UUID definitionId,
                                            String idempotencyKey, Map<String, Object> inputJson,
                                            String correlationId) {
        var exec = new WorkflowExecution();
        exec.tenantId = tenantId;
        exec.definitionId = definitionId;
        exec.idempotencyKey = idempotencyKey;
        exec.inputJson = inputJson != null ? inputJson : Map.of();
        exec.correlationId = correlationId;
        exec.status = ExecutionStatus.PENDING;
        exec.createdAt = Instant.now();
        return exec;
    }

    public void transitionTo(ExecutionStatus newStatus) {
        ExecutionStateMachine.validate(this.status, newStatus);
        this.status = newStatus;
        switch (newStatus) {
            case RUNNING -> this.startedAt = Instant.now();
            case COMPLETED -> this.completedAt = Instant.now();
            case CANCELED -> this.canceledAt = Instant.now();
            case TIMED_OUT -> this.timedOutAt = Instant.now();
        }
    }

    public void fail(String errorMessage) {
        transitionTo(ExecutionStatus.FAILED);
        this.errorMessage = errorMessage;
    }

    // Getters
    public UUID getExecutionId() { return executionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDefinitionId() { return definitionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public ExecutionStatus getStatus() { return status; }
    public Map<String, Object> getInputJson() { return inputJson; }
    public Map<String, Object> getOutputJson() { return outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public Instant getTimedOutAt() { return timedOutAt; }
    public String getCorrelationId() { return correlationId; }
    public void setOutputJson(Map<String, Object> outputJson) { this.outputJson = outputJson; }
}
```

### Step 3.4 — Create ExecutionStateMachine.java

```java
package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.ExecutionStatus;

import java.util.Map;
import java.util.Set;

public final class ExecutionStateMachine {

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> ALLOWED_TRANSITIONS = Map.of(
            ExecutionStatus.PENDING, Set.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELED),
            ExecutionStatus.RUNNING, Set.of(ExecutionStatus.WAITING, ExecutionStatus.COMPLETED,
                    ExecutionStatus.FAILED, ExecutionStatus.CANCELED, ExecutionStatus.TIMED_OUT),
            ExecutionStatus.WAITING, Set.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELED, ExecutionStatus.TIMED_OUT),
            ExecutionStatus.FAILED, Set.of(ExecutionStatus.COMPENSATING),
            ExecutionStatus.COMPENSATING, Set.of(ExecutionStatus.COMPENSATED, ExecutionStatus.COMPENSATION_FAILED),
            // Terminal states — no transitions allowed
            ExecutionStatus.COMPLETED, Set.of(),
            ExecutionStatus.COMPENSATED, Set.of(),
            ExecutionStatus.COMPENSATION_FAILED, Set.of(),
            ExecutionStatus.CANCELED, Set.of(),
            ExecutionStatus.TIMED_OUT, Set.of()
    );

    private ExecutionStateMachine() {}

    public static void validate(ExecutionStatus from, ExecutionStatus to) {
        Set<ExecutionStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalStateException(
                    "Invalid execution transition: " + from + " → " + to);
        }
    }

    public static boolean canTransition(ExecutionStatus from, ExecutionStatus to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
```

### Step 3.5 — Create WorkflowExecutionRepository.java

```java
package com.atlas.workflow.repository;

import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Optional<WorkflowExecution> findByExecutionIdAndTenantId(UUID executionId, UUID tenantId);

    Optional<WorkflowExecution> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<WorkflowExecution> findAllByTenantIdAndStatus(UUID tenantId, ExecutionStatus status);
}
```

### Step 3.6 — Create ExecutionStateMachineTest.java

```java
package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class ExecutionStateMachineTest {

    @ParameterizedTest(name = "{0} → {1} is valid")
    @CsvSource({
            "PENDING,  RUNNING",
            "PENDING,  CANCELED",
            "RUNNING,  WAITING",
            "RUNNING,  COMPLETED",
            "RUNNING,  FAILED",
            "RUNNING,  CANCELED",
            "RUNNING,  TIMED_OUT",
            "WAITING,  RUNNING",
            "WAITING,  CANCELED",
            "WAITING,  TIMED_OUT",
            "FAILED,   COMPENSATING",
            "COMPENSATING, COMPENSATED",
            "COMPENSATING, COMPENSATION_FAILED"
    })
    void validTransition(String from, String to) {
        assertThatNoException().isThrownBy(() ->
                ExecutionStateMachine.validate(
                        ExecutionStatus.valueOf(from),
                        ExecutionStatus.valueOf(to)
                )
        );
    }

    @ParameterizedTest(name = "{0} → {1} is invalid")
    @CsvSource({
            "PENDING,  COMPLETED",
            "PENDING,  COMPENSATING",
            "RUNNING,  PENDING",
            "COMPLETED, RUNNING",
            "COMPLETED, FAILED",
            "CANCELED, RUNNING",
            "TIMED_OUT, RUNNING",
            "COMPENSATED, COMPENSATING",
            "COMPENSATION_FAILED, COMPENSATING"
    })
    void invalidTransition(String from, String to) {
        assertThatThrownBy(() ->
                ExecutionStateMachine.validate(
                        ExecutionStatus.valueOf(from),
                        ExecutionStatus.valueOf(to)
                )
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Invalid execution transition");
    }

    @Test
    void canTransition_returnsTrue_forValidTransition() {
        assertThat(ExecutionStateMachine.canTransition(ExecutionStatus.PENDING, ExecutionStatus.RUNNING)).isTrue();
    }

    @Test
    void canTransition_returnsFalse_forInvalidTransition() {
        assertThat(ExecutionStateMachine.canTransition(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)).isFalse();
    }
}
```

### Step 3.7 — Run tests

```bash
mvn -pl workflow-service test -Dtest=ExecutionStateMachineTest
```

**Git commit message:** `feat(workflow-service): workflow execution entity, ExecutionStatus enum, state machine with validated transitions`

---

## Task 4: Step Execution Domain and State Machine

**Files to create:**
- `workflow-service/src/main/resources/db/migration/V004__create_step_executions.sql`
- `workflow-service/src/main/java/com/atlas/workflow/domain/StepStatus.java`
- `workflow-service/src/main/java/com/atlas/workflow/domain/StepExecution.java`
- `workflow-service/src/main/java/com/atlas/workflow/repository/StepExecutionRepository.java`
- `workflow-service/src/main/java/com/atlas/workflow/statemachine/StepStateMachine.java`
- `workflow-service/src/test/java/com/atlas/workflow/statemachine/StepStateMachineTest.java`

### Step 4.1 — Create V004__create_step_executions.sql

```sql
CREATE TABLE workflow.step_executions (
    step_execution_id  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id       UUID         NOT NULL REFERENCES workflow.workflow_executions(execution_id),
    tenant_id          UUID         NOT NULL,
    step_name          VARCHAR(255) NOT NULL,
    step_index         INT          NOT NULL,
    step_type          VARCHAR(50)  NOT NULL,
    status             VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    attempt_count      INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 1,
    timeout_ms         BIGINT,
    input_json         JSONB        NOT NULL DEFAULT '{}',
    output_json        JSONB,
    error_message      TEXT,
    next_retry_at      TIMESTAMPTZ,
    leased_at          TIMESTAMPTZ,
    leased_by          VARCHAR(255),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    state_history      JSONB        NOT NULL DEFAULT '[]',
    compensation_for   VARCHAR(255),
    is_compensation    BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_step_executions_execution ON workflow.step_executions (execution_id);
CREATE INDEX idx_step_executions_tenant    ON workflow.step_executions (tenant_id);
CREATE INDEX idx_step_executions_status    ON workflow.step_executions (tenant_id, status);
CREATE INDEX idx_step_executions_retry     ON workflow.step_executions (next_retry_at) WHERE status = 'RETRY_SCHEDULED';
```

### Step 4.2 — Create StepStatus.java

```java
package com.atlas.workflow.domain;

public enum StepStatus {
    PENDING,
    LEASED,
    RUNNING,
    SUCCEEDED,
    WAITING,
    FAILED,
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}
```

### Step 4.3 — Create StepExecution.java

```java
package com.atlas.workflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "step_executions", schema = "workflow")
public class StepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "step_execution_id")
    private UUID stepExecutionId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "step_type", nullable = false)
    private String stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 1;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Map<String, Object> inputJson = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private Map<String, Object> outputJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "leased_at")
    private Instant leasedAt;

    @Column(name = "leased_by")
    private String leasedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> stateHistory = new ArrayList<>();

    @Column(name = "compensation_for")
    private String compensationFor;

    @Column(name = "is_compensation", nullable = false)
    private boolean compensation = false;

    protected StepExecution() {}

    public static StepExecution create(UUID executionId, UUID tenantId, String stepName,
                                        int stepIndex, String stepType, int maxAttempts,
                                        Long timeoutMs, Map<String, Object> inputJson) {
        var step = new StepExecution();
        step.executionId = executionId;
        step.tenantId = tenantId;
        step.stepName = stepName;
        step.stepIndex = stepIndex;
        step.stepType = stepType;
        step.maxAttempts = maxAttempts;
        step.timeoutMs = timeoutMs;
        step.inputJson = inputJson != null ? inputJson : Map.of();
        step.status = StepStatus.PENDING;
        step.attemptCount = 0;
        step.appendHistory(StepStatus.PENDING, null);
        return step;
    }

    public void transitionTo(StepStatus newStatus) {
        StepStateMachine.validate(this.status, newStatus);
        this.status = newStatus;
        appendHistory(newStatus, null);
        switch (newStatus) {
            case RUNNING -> {
                this.startedAt = Instant.now();
                this.attemptCount++;
            }
            case SUCCEEDED, DEAD_LETTERED, COMPENSATED, COMPENSATION_FAILED ->
                    this.completedAt = Instant.now();
        }
    }

    public void scheduleRetry(Instant nextRetryAt) {
        transitionTo(StepStatus.RETRY_SCHEDULED);
        this.nextRetryAt = nextRetryAt;
    }

    public void lease(String workerId) {
        transitionTo(StepStatus.LEASED);
        this.leasedAt = Instant.now();
        this.leasedBy = workerId;
    }

    private void appendHistory(StepStatus status, String error) {
        this.stateHistory.add(Map.of(
                "status", status.name(),
                "timestamp", Instant.now().toString(),
                "attempt", this.attemptCount
        ));
    }

    // Getters
    public UUID getStepExecutionId() { return stepExecutionId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getTenantId() { return tenantId; }
    public String getStepName() { return stepName; }
    public int getStepIndex() { return stepIndex; }
    public String getStepType() { return stepType; }
    public StepStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Long getTimeoutMs() { return timeoutMs; }
    public Map<String, Object> getInputJson() { return inputJson; }
    public Map<String, Object> getOutputJson() { return outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getLeasedAt() { return leasedAt; }
    public String getLeasedBy() { return leasedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<Map<String, Object>> getStateHistory() { return stateHistory; }
    public String getCompensationFor() { return compensationFor; }
    public boolean isCompensation() { return compensation; }

    public void setOutputJson(Map<String, Object> outputJson) { this.outputJson = outputJson; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setCompensationFor(String stepName) { this.compensationFor = stepName; this.compensation = true; }
}
```

### Step 4.4 — Create StepStateMachine.java

```java
package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.StepStatus;

import java.util.Map;
import java.util.Set;

public final class StepStateMachine {

    private static final Map<StepStatus, Set<StepStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(StepStatus.PENDING,      Set.of(StepStatus.LEASED)),
            Map.entry(StepStatus.LEASED,       Set.of(StepStatus.RUNNING, StepStatus.PENDING)),
            Map.entry(StepStatus.RUNNING,      Set.of(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.WAITING)),
            Map.entry(StepStatus.WAITING,      Set.of(StepStatus.SUCCEEDED, StepStatus.FAILED)),
            Map.entry(StepStatus.FAILED,       Set.of(StepStatus.RETRY_SCHEDULED, StepStatus.DEAD_LETTERED,
                                                       StepStatus.COMPENSATING)),
            Map.entry(StepStatus.RETRY_SCHEDULED, Set.of(StepStatus.PENDING)),
            Map.entry(StepStatus.SUCCEEDED,    Set.of(StepStatus.COMPENSATING)),
            Map.entry(StepStatus.COMPENSATING, Set.of(StepStatus.COMPENSATED, StepStatus.COMPENSATION_FAILED)),
            Map.entry(StepStatus.COMPENSATION_FAILED, Set.of(StepStatus.DEAD_LETTERED)),
            // Terminal states
            Map.entry(StepStatus.DEAD_LETTERED,   Set.of()),
            Map.entry(StepStatus.COMPENSATED,     Set.of())
    );

    private StepStateMachine() {}

    public static void validate(StepStatus from, StepStatus to) {
        Set<StepStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalStateException(
                    "Invalid step transition: " + from + " → " + to);
        }
    }

    public static boolean canTransition(StepStatus from, StepStatus to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
```

### Step 4.5 — Create StepExecutionRepository.java

```java
package com.atlas.workflow.repository;

import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {

    List<StepExecution> findAllByExecutionIdOrderByStepIndexAsc(UUID executionId);

    Optional<StepExecution> findFirstByExecutionIdAndStatusOrderByStepIndexAsc(UUID executionId, StepStatus status);

    List<StepExecution> findAllByExecutionIdAndStatus(UUID executionId, StepStatus status);

    @Query("SELECT s FROM StepExecution s WHERE s.status = 'RETRY_SCHEDULED' AND s.nextRetryAt <= :now")
    List<StepExecution> findDueForRetry(@Param("now") Instant now);

    @Query("SELECT s FROM StepExecution s WHERE s.status IN ('RUNNING', 'LEASED') AND s.startedAt <= :cutoff")
    List<StepExecution> findTimedOut(@Param("cutoff") Instant cutoff);
}
```

### Step 4.6 — Create StepStateMachineTest.java

```java
package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.StepStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class StepStateMachineTest {

    @ParameterizedTest(name = "{0} → {1} is valid")
    @CsvSource({
            "PENDING,           LEASED",
            "LEASED,            RUNNING",
            "LEASED,            PENDING",
            "RUNNING,           SUCCEEDED",
            "RUNNING,           FAILED",
            "RUNNING,           WAITING",
            "WAITING,           SUCCEEDED",
            "WAITING,           FAILED",
            "FAILED,            RETRY_SCHEDULED",
            "FAILED,            DEAD_LETTERED",
            "FAILED,            COMPENSATING",
            "RETRY_SCHEDULED,   PENDING",
            "SUCCEEDED,         COMPENSATING",
            "COMPENSATING,      COMPENSATED",
            "COMPENSATING,      COMPENSATION_FAILED",
            "COMPENSATION_FAILED, DEAD_LETTERED"
    })
    void validTransition(String from, String to) {
        assertThatNoException().isThrownBy(() ->
                StepStateMachine.validate(
                        StepStatus.valueOf(from.trim()),
                        StepStatus.valueOf(to.trim())
                )
        );
    }

    @ParameterizedTest(name = "{0} → {1} is invalid")
    @CsvSource({
            "PENDING,     RUNNING",
            "PENDING,     SUCCEEDED",
            "RUNNING,     PENDING",
            "RUNNING,     RETRY_SCHEDULED",
            "SUCCEEDED,   RUNNING",
            "DEAD_LETTERED, PENDING",
            "COMPENSATED, PENDING",
            "COMPENSATED, COMPENSATING"
    })
    void invalidTransition(String from, String to) {
        assertThatThrownBy(() ->
                StepStateMachine.validate(
                        StepStatus.valueOf(from.trim()),
                        StepStatus.valueOf(to.trim())
                )
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Invalid step transition");
    }
}
```

### Step 4.7 — Run tests

```bash
mvn -pl workflow-service test -Dtest=StepStateMachineTest
```

**Git commit message:** `feat(workflow-service): step execution entity, StepStatus enum, step state machine with validated transitions`

---

## Task 5: Execution Engine — Start Workflow

**Files to create:**
- `workflow-service/src/main/resources/db/migration/V005__create_outbox.sql`
- `workflow-service/src/main/java/com/atlas/workflow/domain/OutboxEvent.java`
- `workflow-service/src/main/java/com/atlas/workflow/repository/OutboxRepository.java`
- `workflow-service/src/main/java/com/atlas/workflow/event/OutboxPublisher.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/WorkflowExecutionService.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/StartExecutionRequest.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/ExecutionResponse.java`
- `workflow-service/src/main/java/com/atlas/workflow/controller/WorkflowExecutionController.java`
- `workflow-service/src/test/java/com/atlas/workflow/controller/WorkflowExecutionControllerIT.java`

### Step 5.1 — Create V005__create_outbox.sql

```sql
CREATE TABLE workflow.outbox (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    payload        JSONB        NOT NULL,
    tenant_id      UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON workflow.outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON workflow.outbox (aggregate_type, aggregate_id, created_at);
```

### Step 5.2 — Create OutboxEvent.java (entity)

Note: This is the JPA entity — distinct from the `com.atlas.common.event.OutboxEvent` record which is the common value type. This entity maps to the `workflow.outbox` table.

```java
package com.atlas.workflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox", schema = "workflow")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public static OutboxEvent create(String aggregateType, String aggregateId,
                                      String eventType, String topic,
                                      Map<String, Object> payload, UUID tenantId) {
        var event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.tenantId = tenantId;
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public Map<String, Object> getPayload() { return payload; }
    public UUID getTenantId() { return tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
```

### Step 5.3 — Create OutboxRepository.java

```java
package com.atlas.workflow.repository;

import com.atlas.workflow.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEvent> findUnpublished();

    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC LIMIT 100")
    List<OutboxEvent> findUnpublishedBatch();
}
```

### Step 5.4 — Create OutboxPublisher.java

```java
package com.atlas.workflow.event;

import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepository.findUnpublishedBatch();
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(),
                        event.getTenantId().toString(),
                        event.getPayload())
                        .get(); // synchronous send — fail fast so row stays unpublished
                event.markPublished();
                outboxRepository.save(event);
            } catch (Exception e) {
                log.warn("Failed to publish outbox event {} to topic {}: {}",
                        event.getId(), event.getTopic(), e.getMessage());
            }
        }
    }
}
```

### Step 5.5 — Create StartExecutionRequest.java

```java
package com.atlas.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record StartExecutionRequest(
        @NotNull UUID definitionId,
        @NotBlank @Size(max = 255) String idempotencyKey,
        Map<String, Object> input
) {}
```

### Step 5.6 — Create ExecutionResponse.java

```java
package com.atlas.workflow.dto;

import com.atlas.workflow.domain.WorkflowExecution;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionResponse(
        UUID executionId,
        UUID tenantId,
        UUID definitionId,
        String idempotencyKey,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant canceledAt
) {
    public static ExecutionResponse from(WorkflowExecution exec) {
        return new ExecutionResponse(
                exec.getExecutionId(),
                exec.getTenantId(),
                exec.getDefinitionId(),
                exec.getIdempotencyKey(),
                exec.getStatus().name(),
                exec.getInputJson(),
                exec.getOutputJson(),
                exec.getErrorMessage(),
                exec.getCreatedAt(),
                exec.getStartedAt(),
                exec.getCompletedAt(),
                exec.getCanceledAt()
        );
    }
}
```

### Step 5.7 — Create WorkflowExecutionService.java

This is the orchestration core. `startExecution` creates the execution + first step, and writes the step command to the outbox atomically.

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class WorkflowExecutionService {

    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final OutboxRepository outboxRepository;

    public WorkflowExecutionService(WorkflowExecutionRepository executionRepository,
                                     StepExecutionRepository stepRepository,
                                     WorkflowDefinitionRepository definitionRepository,
                                     OutboxRepository outboxRepository) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.definitionRepository = definitionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public WorkflowExecution startExecution(StartExecutionRequest request, UUID tenantId, String correlationId) {
        // Idempotency check
        Optional<WorkflowExecution> existing = executionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // Validate definition is published
        WorkflowDefinition definition = definitionRepository
                .findByDefinitionIdAndTenantId(request.definitionId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + request.definitionId()));

        if (definition.getStatus() != DefinitionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Definition '" + definition.getName() + "' v" + definition.getVersion()
                            + " is not published. Current status: " + definition.getStatus());
        }

        // Create execution
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                definition.getDefinitionId(),
                request.idempotencyKey(),
                request.input(),
                correlationId
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        // Parse steps from definition JSON
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) definition.getStepsJson();

        if (steps == null || steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow definition has no steps");
        }

        // Create first step
        Map<String, Object> firstStepDef = steps.get(0);
        StepExecution firstStep = createStepExecution(execution, firstStepDef, 0, request.input());
        firstStep = stepRepository.save(firstStep);

        // Publish step execute command via outbox
        publishStepExecuteCommand(firstStep, execution);

        return execution;
    }

    public WorkflowExecution getById(UUID executionId, UUID tenantId) {
        return executionRepository.findByExecutionIdAndTenantId(executionId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow execution not found: " + executionId));
    }

    private StepExecution createStepExecution(WorkflowExecution execution,
                                               Map<String, Object> stepDef,
                                               int stepIndex,
                                               Map<String, Object> input) {
        String stepName = (String) stepDef.getOrDefault("name", "step-" + stepIndex);
        String stepType = (String) stepDef.getOrDefault("type", "INTERNAL_COMMAND");
        int maxAttempts = extractMaxAttempts(stepDef);
        Long timeoutMs = stepDef.containsKey("timeout_ms")
                ? ((Number) stepDef.get("timeout_ms")).longValue()
                : null;

        return StepExecution.create(
                execution.getExecutionId(),
                execution.getTenantId(),
                stepName,
                stepIndex,
                stepType,
                maxAttempts,
                timeoutMs,
                input
        );
    }

    private int extractMaxAttempts(Map<String, Object> stepDef) {
        if (stepDef.containsKey("retry_policy")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> retryPolicy = (Map<String, Object>) stepDef.get("retry_policy");
            if (retryPolicy != null && retryPolicy.containsKey("max_attempts")) {
                return ((Number) retryPolicy.get("max_attempts")).intValue();
            }
        }
        return 1;
    }

    private void publishStepExecuteCommand(StepExecution step, WorkflowExecution execution) {
        Map<String, Object> payload = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "tenant_id", step.getTenantId().toString(),
                "step_name", step.getStepName(),
                "step_type", step.getStepType(),
                "attempt", step.getAttemptCount(),
                "input", step.getInputJson(),
                "timeout_ms", step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000
        );

        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution",
                step.getStepExecutionId().toString(),
                "step.execute",
                "workflow.steps.execute",
                payload,
                step.getTenantId()
        );
        outboxRepository.save(outboxEvent);
    }
}
```

### Step 5.8 — Create WorkflowExecutionController.java

```java
package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.ExecutionResponse;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.service.WorkflowExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    public WorkflowExecutionController(WorkflowExecutionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionResponse startExecution(
            @Valid @RequestBody StartExecutionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {
        String correlationId = httpRequest.getHeader("X-Correlation-ID");
        return ExecutionResponse.from(
                service.startExecution(request, principal.tenantId(), correlationId));
    }

    @GetMapping("/{id}")
    public ExecutionResponse getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ExecutionResponse.from(service.getById(id, principal.tenantId()));
    }
}
```

### Step 5.9 — Create WorkflowExecutionControllerIT.java

```java
package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkflowExecutionControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    private UUID publishedDefinitionId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001"); // fixed test tenant
        var definition = WorkflowDefinition.create(
                tenantId, "test-workflow", 1,
                List.of(Map.of("name", "step1", "type", "INTERNAL_COMMAND",
                               "retry_policy", Map.of("max_attempts", 3))),
                Map.of(), "API"
        );
        definition.publish();
        publishedDefinitionId = definitionRepository.save(definition).getDefinitionId();
    }

    private HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setBearerAuth("test-jwt-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void startExecution_publishedDefinition_returns201() {
        var request = Map.of(
                "definitionId", publishedDefinitionId.toString(),
                "idempotencyKey", "idem-" + UUID.randomUUID(),
                "input", Map.of("orderId", "order-123")
        );

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions",
                HttpMethod.POST,
                new HttpEntity<>(request, headers()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("executionId");
        assertThat(response.getBody().get("status")).isEqualTo("RUNNING");
    }

    @Test
    void startExecution_idempotent_returnsSameExecution() {
        String idempotencyKey = "idem-idempotent-" + UUID.randomUUID();
        var request = Map.of(
                "definitionId", publishedDefinitionId.toString(),
                "idempotencyKey", idempotencyKey,
                "input", Map.of()
        );

        ResponseEntity<Map> first = restTemplate.exchange(
                "/api/v1/workflow-executions", HttpMethod.POST,
                new HttpEntity<>(request, headers()), Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
                "/api/v1/workflow-executions", HttpMethod.POST,
                new HttpEntity<>(request, headers()), Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("executionId"))
                .isEqualTo(second.getBody().get("executionId"));
    }

    @Test
    void startExecution_draftDefinition_returns422() {
        // Create a DRAFT definition
        var draft = WorkflowDefinition.create(tenantId, "draft-wf", 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        UUID draftId = definitionRepository.save(draft).getDefinitionId();

        var request = Map.of(
                "definitionId", draftId.toString(),
                "idempotencyKey", "idem-draft-" + UUID.randomUUID(),
                "input", Map.of()
        );

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions", HttpMethod.POST,
                new HttpEntity<>(request, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void getExecution_unknownId_returns404() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(null, headers()),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

### Step 5.10 — Run tests

```bash
mvn -pl workflow-service test -Dtest=WorkflowExecutionControllerIT
```

**Git commit message:** `feat(workflow-service): execution engine start workflow, outbox table, OutboxPublisher scheduler`

---

## Task 6: Execution Engine — Process Step Results

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/event/StepResultConsumer.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/StepResultProcessor.java`
- `workflow-service/src/test/java/com/atlas/workflow/service/StepResultProcessorIT.java`

### Step 6.1 — Create StepResultProcessor.java

This is the heart of the execution engine. Handles all possible step result outcomes: SUCCEEDED, FAILED, DELAY_REQUESTED, WAITING.

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class StepResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(StepResultProcessor.class);

    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final OutboxRepository outboxRepository;
    private final CompensationEngine compensationEngine;

    public StepResultProcessor(WorkflowExecutionRepository executionRepository,
                                StepExecutionRepository stepRepository,
                                WorkflowDefinitionRepository definitionRepository,
                                OutboxRepository outboxRepository,
                                CompensationEngine compensationEngine) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.definitionRepository = definitionRepository;
        this.outboxRepository = outboxRepository;
        this.compensationEngine = compensationEngine;
    }

    public void process(Map<String, Object> resultPayload) {
        UUID stepExecutionId = UUID.fromString((String) resultPayload.get("step_execution_id"));
        String outcome = (String) resultPayload.get("outcome");
        int attemptCount = ((Number) resultPayload.get("attempt")).intValue();

        StepExecution step = stepRepository.findById(stepExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepExecutionId));

        // Deduplication: skip if already processed for this attempt
        if (step.getAttemptCount() != attemptCount) {
            log.warn("Duplicate result for step {} attempt {}; current attempt is {}. Skipping.",
                    stepExecutionId, attemptCount, step.getAttemptCount());
            return;
        }

        WorkflowExecution execution = executionRepository.findById(step.getExecutionId())
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + step.getExecutionId()));

        // Ignore results for canceled executions
        if (execution.getStatus() == ExecutionStatus.CANCELED) {
            log.info("Ignoring step result for canceled execution {}", execution.getExecutionId());
            return;
        }

        switch (outcome) {
            case "SUCCEEDED" -> handleSucceeded(step, execution, resultPayload);
            case "FAILED" -> handleFailed(step, execution, resultPayload);
            case "DELAY_REQUESTED" -> handleDelayRequested(step, execution, resultPayload);
            case "WAITING" -> handleWaiting(step, execution);
            default -> log.error("Unknown step outcome '{}' for step {}", outcome, stepExecutionId);
        }
    }

    private void handleSucceeded(StepExecution step, WorkflowExecution execution,
                                  Map<String, Object> resultPayload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) resultPayload.getOrDefault("output", Map.of());
        step.setOutputJson(output);
        step.transitionTo(StepStatus.SUCCEEDED);
        stepRepository.save(step);

        // Advance to next step or complete execution
        List<StepExecution> allSteps = stepRepository.findAllByExecutionIdOrderByStepIndexAsc(execution.getExecutionId());
        WorkflowDefinition definition = definitionRepository.findById(execution.getDefinitionId())
                .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) definition.getStepsJson();
        int nextIndex = step.getStepIndex() + 1;

        if (nextIndex < stepDefs.size()) {
            // Create and schedule next step
            Map<String, Object> nextStepDef = stepDefs.get(nextIndex);
            String nextStepName = (String) nextStepDef.getOrDefault("name", "step-" + nextIndex);
            String nextStepType = (String) nextStepDef.getOrDefault("type", "INTERNAL_COMMAND");
            int maxAttempts = extractMaxAttempts(nextStepDef);
            Long timeoutMs = nextStepDef.containsKey("timeout_ms")
                    ? ((Number) nextStepDef.get("timeout_ms")).longValue() : null;

            StepExecution nextStep = StepExecution.create(
                    execution.getExecutionId(), execution.getTenantId(),
                    nextStepName, nextIndex, nextStepType, maxAttempts, timeoutMs, output);
            nextStep = stepRepository.save(nextStep);
            publishStepExecuteCommand(nextStep, execution);

            if (execution.getStatus() == ExecutionStatus.WAITING) {
                execution.transitionTo(ExecutionStatus.RUNNING);
                executionRepository.save(execution);
            }
        } else {
            // All steps succeeded — complete execution
            execution.transitionTo(ExecutionStatus.COMPLETED);
            execution.setOutputJson(output);
            executionRepository.save(execution);
            log.info("Execution {} completed successfully", execution.getExecutionId());
        }
    }

    private void handleFailed(StepExecution step, WorkflowExecution execution,
                               Map<String, Object> resultPayload) {
        String errorMessage = (String) resultPayload.getOrDefault("error", "Unknown error");
        boolean nonRetryable = Boolean.TRUE.equals(resultPayload.get("non_retryable"));
        step.setErrorMessage(errorMessage);
        step.transitionTo(StepStatus.FAILED);

        if (!nonRetryable && step.getAttemptCount() < step.getMaxAttempts()) {
            // Schedule retry
            long backoffMs = computeBackoff(step);
            step.scheduleRetry(Instant.now().plusMillis(backoffMs));
            step.transitionTo(StepStatus.RETRY_SCHEDULED);
            stepRepository.save(step);
            log.info("Step {} scheduled for retry #{} in {}ms",
                    step.getStepName(), step.getAttemptCount() + 1, backoffMs);
        } else {
            // No more retries — dead-letter this step, start compensation
            step.transitionTo(StepStatus.DEAD_LETTERED);
            stepRepository.save(step);
            execution.fail(errorMessage);
            executionRepository.save(execution);
            compensationEngine.startCompensation(execution);
        }
    }

    private void handleDelayRequested(StepExecution step, WorkflowExecution execution,
                                       Map<String, Object> resultPayload) {
        long delayMs = ((Number) resultPayload.getOrDefault("delay_ms", 0)).longValue();
        step.scheduleRetry(Instant.now().plusMillis(delayMs));
        stepRepository.save(step);
        log.info("Step {} delay requested for {}ms", step.getStepName(), delayMs);
    }

    private void handleWaiting(StepExecution step, WorkflowExecution execution) {
        step.transitionTo(StepStatus.WAITING);
        stepRepository.save(step);
        execution.transitionTo(ExecutionStatus.WAITING);
        executionRepository.save(execution);
        log.info("Execution {} waiting on step {}", execution.getExecutionId(), step.getStepName());
    }

    private long computeBackoff(StepExecution step) {
        // Exponential backoff: 1s * 2^(attempt-1), capped at 60s
        long base = 1000L;
        long backoff = base * (1L << Math.min(step.getAttemptCount(), 5));
        return Math.min(backoff, 60_000L);
    }

    private int extractMaxAttempts(Map<String, Object> stepDef) {
        if (stepDef.containsKey("retry_policy")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> retryPolicy = (Map<String, Object>) stepDef.get("retry_policy");
            if (retryPolicy != null && retryPolicy.containsKey("max_attempts")) {
                return ((Number) retryPolicy.get("max_attempts")).intValue();
            }
        }
        return 1;
    }

    private void publishStepExecuteCommand(StepExecution step, WorkflowExecution execution) {
        Map<String, Object> payload = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "tenant_id", step.getTenantId().toString(),
                "step_name", step.getStepName(),
                "step_type", step.getStepType(),
                "attempt", step.getAttemptCount(),
                "input", step.getInputJson(),
                "timeout_ms", step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000
        );
        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution", step.getStepExecutionId().toString(),
                "step.execute", "workflow.steps.execute",
                payload, step.getTenantId());
        outboxRepository.save(outboxEvent);
    }
}
```

### Step 6.2 — Create StepResultConsumer.java

```java
package com.atlas.workflow.event;

import com.atlas.workflow.service.StepResultProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StepResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(StepResultConsumer.class);

    private final StepResultProcessor processor;

    public StepResultConsumer(StepResultProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = "workflow.steps.result",
            groupId = "workflow-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(Map<String, Object> payload) {
        try {
            log.debug("Received step result for step_execution_id={}",
                    payload.get("step_execution_id"));
            processor.process(payload);
        } catch (Exception e) {
            log.error("Failed to process step result {}: {}",
                    payload.get("step_execution_id"), e.getMessage(), e);
            throw e; // re-throw to trigger Kafka retry / DLQ
        }
    }
}
```

### Step 6.3 — Create StepResultProcessorIT.java

```java
package com.atlas.workflow.service;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StepResultProcessorIT {

    @Autowired
    private StepResultProcessor processor;
    @Autowired
    private WorkflowDefinitionRepository definitionRepository;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    @Autowired
    private StepExecutionRepository stepRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private WorkflowDefinition savedPublishedDefinition(int stepCount) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(Map.of("name", "step-" + i, "type", "INTERNAL_COMMAND",
                             "retry_policy", Map.of("max_attempts", 3)));
        }
        var def = WorkflowDefinition.create(tenantId, "test-wf-" + UUID.randomUUID(), 1,
                steps, Map.of(), "API");
        def.publish();
        return definitionRepository.save(def);
    }

    @Test
    void processSucceeded_lastStep_completesExecution() {
        WorkflowDefinition def = savedPublishedDefinition(1);
        WorkflowExecution execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 3, null, Map.of());
        step.lease("worker-1");
        step.transitionTo(StepStatus.RUNNING);
        step = stepRepository.save(step);

        Map<String, Object> result = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attempt", step.getAttemptCount(),
                "output", Map.of("result", "ok")
        );

        processor.process(result);

        StepExecution updatedStep = stepRepository.findById(step.getStepExecutionId()).orElseThrow();
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();

        assertThat(updatedStep.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void processSucceeded_midStep_schedulesNextStep() {
        WorkflowDefinition def = savedPublishedDefinition(2);
        WorkflowExecution execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step0 = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 3, null, Map.of());
        step0.lease("worker-1");
        step0.transitionTo(StepStatus.RUNNING);
        step0 = stepRepository.save(step0);

        processor.process(Map.of(
                "step_execution_id", step0.getStepExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attempt", step0.getAttemptCount(),
                "output", Map.of()
        ));

        List<StepExecution> allSteps = stepRepository.findAllByExecutionIdOrderByStepIndexAsc(execution.getExecutionId());
        assertThat(allSteps).hasSize(2);
        StepExecution nextStep = allSteps.get(1);
        assertThat(nextStep.getStepIndex()).isEqualTo(1);
        assertThat(nextStep.getStatus()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void processFailed_withRetriesLeft_schedulesRetry() {
        WorkflowDefinition def = savedPublishedDefinition(1);
        WorkflowExecution execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 3, null, Map.of());
        step.lease("worker-1");
        step.transitionTo(StepStatus.RUNNING);
        step = stepRepository.save(step);

        processor.process(Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "outcome", "FAILED",
                "attempt", step.getAttemptCount(),
                "error", "transient failure",
                "non_retryable", false
        ));

        StepExecution updated = stepRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        assertThat(updated.getNextRetryAt()).isNotNull();
    }

    @Test
    void processDuplicate_isIgnored() {
        WorkflowDefinition def = savedPublishedDefinition(1);
        WorkflowExecution execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 1, null, Map.of());
        step.lease("worker-1");
        step.transitionTo(StepStatus.RUNNING);
        step = stepRepository.save(step);

        Map<String, Object> result = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attempt", step.getAttemptCount(),
                "output", Map.of()
        );

        processor.process(result);
        // Second call with same attempt number should be ignored (already processed)
        processor.process(result);

        StepExecution updated = stepRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
    }
}
```

### Step 6.4 — Run tests

```bash
mvn -pl workflow-service test -Dtest=StepResultProcessorIT
```

**Git commit message:** `feat(workflow-service): step result consumer, process SUCCEEDED/FAILED/DELAY/WAITING outcomes, deduplication`

---

## Task 7: Compensation Engine

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/service/CompensationEngine.java`
- `workflow-service/src/test/java/com/atlas/workflow/service/CompensationEngineIT.java`

### Step 7.1 — Create CompensationEngine.java

The compensation engine runs completed steps in reverse order. For each completed step that has a compensation defined in the workflow definition, it creates a compensation step execution and publishes the execute command.

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CompensationEngine {

    private static final Logger log = LoggerFactory.getLogger(CompensationEngine.class);

    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final OutboxRepository outboxRepository;

    public CompensationEngine(WorkflowExecutionRepository executionRepository,
                               StepExecutionRepository stepRepository,
                               WorkflowDefinitionRepository definitionRepository,
                               OutboxRepository outboxRepository) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.definitionRepository = definitionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void startCompensation(WorkflowExecution execution) {
        execution.transitionTo(ExecutionStatus.COMPENSATING);
        executionRepository.save(execution);
        log.info("Starting compensation for execution {}", execution.getExecutionId());

        WorkflowDefinition definition = definitionRepository
                .findById(execution.getDefinitionId()).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> compensationDefs =
                (Map<String, Object>) definition.getCompensationsJson();

        // Find all SUCCEEDED steps (these need compensation), in reverse order
        List<StepExecution> succeededSteps = stepRepository
                .findAllByExecutionIdAndStatus(execution.getExecutionId(), StepStatus.SUCCEEDED);

        succeededSteps.sort(Comparator.comparingInt(StepExecution::getStepIndex).reversed());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) definition.getStepsJson();

        boolean anyCompensationScheduled = false;
        for (StepExecution succeededStep : succeededSteps) {
            String compensationKey = findCompensationKey(succeededStep.getStepName(), stepDefs);
            if (compensationKey != null && compensationDefs.containsKey(compensationKey)) {
                scheduleCompensationStep(succeededStep, compensationKey,
                        compensationDefs.get(compensationKey), execution);
                anyCompensationScheduled = true;
            } else {
                log.debug("No compensation defined for step '{}', skipping",
                        succeededStep.getStepName());
            }
        }

        if (!anyCompensationScheduled) {
            // No compensation steps needed — directly transition to COMPENSATED
            execution.transitionTo(ExecutionStatus.COMPENSATED);
            executionRepository.save(execution);
            log.info("Execution {} compensated (no compensation steps needed)",
                    execution.getExecutionId());
        }
    }

    @Transactional
    public void handleCompensationStepResult(StepExecution compensationStep,
                                              WorkflowExecution execution,
                                              boolean succeeded,
                                              String errorMessage) {
        if (succeeded) {
            compensationStep.transitionTo(StepStatus.COMPENSATED);
            stepRepository.save(compensationStep);
        } else {
            compensationStep.setErrorMessage(errorMessage);
            compensationStep.transitionTo(StepStatus.COMPENSATION_FAILED);
            compensationStep.transitionTo(StepStatus.DEAD_LETTERED);
            stepRepository.save(compensationStep);

            execution.transitionTo(ExecutionStatus.COMPENSATION_FAILED);
            executionRepository.save(execution);
            log.error("Compensation failed for execution {} on step {}",
                    execution.getExecutionId(), compensationStep.getStepName());
            return;
        }

        // Check if all compensation steps are done
        List<StepExecution> allCompensationSteps = stepRepository
                .findAllByExecutionIdAndStatus(execution.getExecutionId(), StepStatus.COMPENSATING);

        // Re-load all compensation steps (not just COMPENSATING ones)
        List<StepExecution> allSteps = stepRepository
                .findAllByExecutionIdOrderByStepIndexAsc(execution.getExecutionId());
        boolean allDone = allSteps.stream()
                .filter(StepExecution::isCompensation)
                .allMatch(s -> s.getStatus() == StepStatus.COMPENSATED
                        || s.getStatus() == StepStatus.DEAD_LETTERED);

        if (allDone) {
            execution.transitionTo(ExecutionStatus.COMPENSATED);
            executionRepository.save(execution);
            log.info("Execution {} fully compensated", execution.getExecutionId());
        }
    }

    private String findCompensationKey(String stepName, List<Map<String, Object>> stepDefs) {
        return stepDefs.stream()
                .filter(def -> stepName.equals(def.get("name")))
                .map(def -> (String) def.get("compensation"))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void scheduleCompensationStep(StepExecution originalStep, String compensationKey,
                                           Object compensationDef, WorkflowExecution execution) {
        @SuppressWarnings("unchecked")
        Map<String, Object> compDefMap = (Map<String, Object>) compensationDef;
        String stepType = (String) compDefMap.getOrDefault("type", "INTERNAL_COMMAND");

        StepExecution compStep = StepExecution.create(
                execution.getExecutionId(),
                execution.getTenantId(),
                compensationKey,
                originalStep.getStepIndex(),
                stepType,
                3, // max attempts for compensation
                30_000L,
                originalStep.getOutputJson() != null ? originalStep.getOutputJson() : Map.of()
        );
        compStep.setCompensationFor(originalStep.getStepName());
        compStep = stepRepository.save(compStep);

        originalStep.transitionTo(StepStatus.COMPENSATING);
        stepRepository.save(originalStep);

        // Publish compensation step command via outbox
        Map<String, Object> payload = Map.of(
                "step_execution_id", compStep.getStepExecutionId().toString(),
                "execution_id", compStep.getExecutionId().toString(),
                "tenant_id", compStep.getTenantId().toString(),
                "step_name", compStep.getStepName(),
                "step_type", compStep.getStepType(),
                "attempt", 0,
                "input", compStep.getInputJson(),
                "is_compensation", true,
                "compensation_for", originalStep.getStepName(),
                "timeout_ms", 30000
        );
        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution", compStep.getStepExecutionId().toString(),
                "step.execute", "workflow.steps.execute",
                payload, compStep.getTenantId());
        outboxRepository.save(outboxEvent);

        log.info("Scheduled compensation step '{}' for original step '{}'",
                compensationKey, originalStep.getStepName());
    }
}
```

### Step 7.2 — Wire compensation into StepResultProcessor

In `StepResultProcessor.java`, the `CompensationEngine` is already injected (injected in Task 6 constructor). The `handleFailed` method already calls `compensationEngine.startCompensation(execution)`. No additional wiring needed.

Also update `handleSucceeded` to check if the incoming step is a compensation step and delegate to `CompensationEngine.handleCompensationStepResult`:

In `StepResultProcessor.handleSucceeded`, add a check at the top:

```java
// Check if this is a compensation step result
if (step.isCompensation()) {
    compensationEngine.handleCompensationStepResult(step, execution, true, null);
    return;
}
```

And in `handleFailed`, before the retry logic check:

```java
// If this is a compensation step failure
if (step.isCompensation()) {
    compensationEngine.handleCompensationStepResult(step, execution, false, errorMessage);
    return;
}
```

### Step 7.3 — Create CompensationEngineIT.java

```java
package com.atlas.workflow.service;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CompensationEngineIT {

    @Autowired
    private CompensationEngine compensationEngine;
    @Autowired
    private WorkflowDefinitionRepository definitionRepository;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    @Autowired
    private StepExecutionRepository stepRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void startCompensation_schedulesCompensationSteps_inReverseOrder() {
        // Define workflow with 2 compensatable steps
        List<Map<String, Object>> steps = List.of(
                Map.of("name", "step-0", "type", "INTERNAL_COMMAND", "compensation", "comp-step-0"),
                Map.of("name", "step-1", "type", "INTERNAL_COMMAND", "compensation", "comp-step-1")
        );
        Map<String, Object> compensations = Map.of(
                "comp-step-0", Map.of("type", "INTERNAL_COMMAND"),
                "comp-step-1", Map.of("type", "INTERNAL_COMMAND")
        );

        var def = WorkflowDefinition.create(tenantId, "comp-wf-" + UUID.randomUUID(), 1,
                steps, compensations, "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-comp-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        // Both steps succeeded
        StepExecution s0 = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 1, null, Map.of());
        s0.lease("w1"); s0.transitionTo(StepStatus.RUNNING); s0.transitionTo(StepStatus.SUCCEEDED);
        stepRepository.save(s0);

        StepExecution s1 = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-1", 1, "INTERNAL_COMMAND", 1, null, Map.of());
        s1.lease("w1"); s1.transitionTo(StepStatus.RUNNING); s1.transitionTo(StepStatus.SUCCEEDED);
        stepRepository.save(s1);

        execution.fail("simulated failure");
        execution = executionRepository.save(execution);

        compensationEngine.startCompensation(execution);

        WorkflowExecution updated = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        // Two compensation steps should be in outbox
        long outboxCount = outboxRepository.findUnpublished().stream()
                .filter(e -> "step.execute".equals(e.getEventType()))
                .count();
        assertThat(outboxCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void startCompensation_noCompensatableSteps_transitionsToCompensated() {
        // Steps with no compensation defined
        List<Map<String, Object>> steps = List.of(
                Map.of("name", "step-0", "type", "INTERNAL_COMMAND")
        );

        var def = WorkflowDefinition.create(tenantId, "no-comp-wf-" + UUID.randomUUID(), 1,
                steps, Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-nocomp-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution s0 = StepExecution.create(execution.getExecutionId(), tenantId,
                "step-0", 0, "INTERNAL_COMMAND", 1, null, Map.of());
        s0.lease("w1"); s0.transitionTo(StepStatus.RUNNING); s0.transitionTo(StepStatus.SUCCEEDED);
        stepRepository.save(s0);

        execution.fail("failure");
        execution = executionRepository.save(execution);

        compensationEngine.startCompensation(execution);

        WorkflowExecution updated = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.COMPENSATED);
    }
}
```

### Step 7.4 — Run tests

```bash
mvn -pl workflow-service test -Dtest=CompensationEngineIT
```

**Git commit message:** `feat(workflow-service): compensation engine, reverse-order compensation, COMPENSATION_FAILED terminal state`

---

## Task 8: Schedulers

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/scheduler/TimeoutDetector.java`
- `workflow-service/src/main/java/com/atlas/workflow/scheduler/RetryScheduler.java`
- `workflow-service/src/main/java/com/atlas/workflow/scheduler/OutboxCleanupScheduler.java`

### Step 8.1 — Create TimeoutDetector.java

Detects steps that have been RUNNING or LEASED past their configured timeout. Marks them FAILED and triggers compensation or retry.

```java
package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import com.atlas.workflow.service.StepResultProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class TimeoutDetector {

    private static final Logger log = LoggerFactory.getLogger(TimeoutDetector.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final StepExecutionRepository stepRepository;
    private final StepResultProcessor stepResultProcessor;

    public TimeoutDetector(StepExecutionRepository stepRepository,
                           StepResultProcessor stepResultProcessor) {
        this.stepRepository = stepRepository;
        this.stepResultProcessor = stepResultProcessor;
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void detectTimedOutSteps() {
        Instant cutoff = Instant.now().minusMillis(DEFAULT_TIMEOUT_MS);
        List<StepExecution> timedOutSteps = stepRepository.findTimedOut(cutoff);

        for (StepExecution step : timedOutSteps) {
            if (step.getTimeoutMs() != null) {
                Instant stepCutoff = step.getStartedAt() != null
                        ? step.getStartedAt().plusMillis(step.getTimeoutMs())
                        : Instant.now();
                if (Instant.now().isBefore(stepCutoff)) {
                    continue; // Not yet timed out based on per-step timeout
                }
            }

            log.warn("Step {} (execution={}) timed out after {}ms",
                    step.getStepName(), step.getExecutionId(),
                    step.getTimeoutMs() != null ? step.getTimeoutMs() : DEFAULT_TIMEOUT_MS);

            // Synthesize a FAILED result for this timed-out step
            Map<String, Object> failedResult = Map.of(
                    "step_execution_id", step.getStepExecutionId().toString(),
                    "outcome", "FAILED",
                    "attempt", step.getAttemptCount(),
                    "error", "Step timed out after " +
                            (step.getTimeoutMs() != null ? step.getTimeoutMs() : DEFAULT_TIMEOUT_MS) + "ms",
                    "non_retryable", false
            );

            try {
                stepResultProcessor.process(failedResult);
            } catch (Exception e) {
                log.error("Failed to process timeout for step {}: {}", step.getStepExecutionId(), e.getMessage());
            }
        }
    }
}
```

### Step 8.2 — Create RetryScheduler.java

Picks up `RETRY_SCHEDULED` steps whose `next_retry_at` has passed and re-publishes their execute command.

```java
package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final StepExecutionRepository stepRepository;
    private final OutboxRepository outboxRepository;

    public RetryScheduler(StepExecutionRepository stepRepository,
                          OutboxRepository outboxRepository) {
        this.stepRepository = stepRepository;
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelay = 1_000)
    @Transactional
    public void scheduleRetries() {
        List<StepExecution> dueSteps = stepRepository.findDueForRetry(Instant.now());

        for (StepExecution step : dueSteps) {
            log.info("Re-publishing step {} for retry attempt {}",
                    step.getStepName(), step.getAttemptCount() + 1);

            step.transitionTo(StepStatus.PENDING);
            stepRepository.save(step);

            Map<String, Object> payload = Map.of(
                    "step_execution_id", step.getStepExecutionId().toString(),
                    "execution_id", step.getExecutionId().toString(),
                    "tenant_id", step.getTenantId().toString(),
                    "step_name", step.getStepName(),
                    "step_type", step.getStepType(),
                    "attempt", step.getAttemptCount(),
                    "input", step.getInputJson(),
                    "timeout_ms", step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000
            );

            OutboxEvent outboxEvent = OutboxEvent.create(
                    "StepExecution", step.getStepExecutionId().toString(),
                    "step.execute", "workflow.steps.execute",
                    payload, step.getTenantId());
            outboxRepository.save(outboxEvent);
        }
    }
}
```

### Step 8.3 — Create OutboxCleanupScheduler.java

Deletes published outbox rows older than 24h.

```java
package com.atlas.workflow.scheduler;

import com.atlas.workflow.repository.OutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Scheduled(fixedDelay = 3_600_000) // 1 hour
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = entityManager.createQuery(
                "DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        if (deleted > 0) {
            log.info("Cleaned up {} published outbox rows older than 24h", deleted);
        }
    }
}
```

### Step 8.4 — Verify schedulers compile

```bash
mvn -pl workflow-service -am compile -q
```

**Git commit message:** `feat(workflow-service): TimeoutDetector, RetryScheduler, OutboxCleanupScheduler`

---

## Task 9: Signal, Cancel, and Timeline APIs

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/dto/SignalRequest.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/TimelineResponse.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/SignalService.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/CancelService.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/TimelineService.java`

**Files to modify:**
- `workflow-service/src/main/java/com/atlas/workflow/controller/WorkflowExecutionController.java` — add signal, cancel, timeline endpoints

**Test files to create:**
- `workflow-service/src/test/java/com/atlas/workflow/controller/SignalCancelTimelineIT.java`

### Step 9.1 — Create DTOs

**SignalRequest.java:**

```java
package com.atlas.workflow.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SignalRequest(
        @NotBlank String stepName,
        Map<String, Object> payload
) {}
```

**TimelineResponse.java:**

```java
package com.atlas.workflow.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TimelineResponse(
        UUID executionId,
        List<TimelineEvent> events
) {
    public record TimelineEvent(
            String timestamp,
            String type,
            String stepName,
            Integer attempt,
            Map<String, Object> detail
    ) {}
}
```

### Step 9.2 — Create SignalService.java

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.dto.SignalRequest;
import com.atlas.workflow.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Transactional
public class SignalService {

    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepRepository;
    private final StepResultProcessor stepResultProcessor;

    public SignalService(WorkflowExecutionRepository executionRepository,
                         StepExecutionRepository stepRepository,
                         StepResultProcessor stepResultProcessor) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.stepResultProcessor = stepResultProcessor;
    }

    public void signal(UUID executionId, UUID tenantId, SignalRequest request) {
        WorkflowExecution execution = executionRepository
                .findByExecutionIdAndTenantId(executionId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Execution not found: " + executionId));

        if (execution.getStatus() != ExecutionStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Execution " + executionId + " is not in WAITING state; current: "
                            + execution.getStatus());
        }

        StepExecution waitingStep = stepRepository
                .findFirstByExecutionIdAndStatusOrderByStepIndexAsc(executionId, StepStatus.WAITING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No WAITING step found for execution " + executionId));

        if (!waitingStep.getStepName().equals(request.stepName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signal step_name '" + request.stepName()
                            + "' does not match waiting step '"
                            + waitingStep.getStepName() + "'");
        }

        // Synthesize a SUCCEEDED result using the signal payload
        java.util.Map<String, Object> result = java.util.Map.of(
                "step_execution_id", waitingStep.getStepExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attempt", waitingStep.getAttemptCount(),
                "output", request.payload() != null ? request.payload() : java.util.Map.of()
        );
        stepResultProcessor.process(result);
    }
}
```

### Step 9.3 — Create CancelService.java

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CancelService {

    private static final Set<ExecutionStatus> CANCELABLE_STATES = Set.of(
            ExecutionStatus.PENDING, ExecutionStatus.RUNNING, ExecutionStatus.WAITING);

    private final WorkflowExecutionRepository executionRepository;

    public CancelService(WorkflowExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    public WorkflowExecution cancel(UUID executionId, UUID tenantId) {
        WorkflowExecution execution = executionRepository
                .findByExecutionIdAndTenantId(executionId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Execution not found: " + executionId));

        if (!CANCELABLE_STATES.contains(execution.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot cancel execution in state " + execution.getStatus()
                            + ". Cancelable states: " + CANCELABLE_STATES);
        }

        execution.transitionTo(ExecutionStatus.CANCELED);
        return executionRepository.save(execution);
    }
}
```

### Step 9.4 — Create TimelineService.java

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.dto.TimelineResponse;
import com.atlas.workflow.dto.TimelineResponse.TimelineEvent;
import com.atlas.workflow.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class TimelineService {

    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepRepository;

    public TimelineService(WorkflowExecutionRepository executionRepository,
                           StepExecutionRepository stepRepository) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
    }

    public TimelineResponse getTimeline(UUID executionId, UUID tenantId) {
        WorkflowExecution execution = executionRepository
                .findByExecutionIdAndTenantId(executionId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Execution not found: " + executionId));

        List<TimelineEvent> events = new ArrayList<>();

        // Execution start event
        events.add(new TimelineEvent(
                execution.getCreatedAt().toString(),
                "EXECUTION_STARTED",
                null, null,
                Map.of("status", execution.getStatus().name())
        ));

        // Step events from state_history JSONB
        List<StepExecution> steps = stepRepository
                .findAllByExecutionIdOrderByStepIndexAsc(executionId);

        for (StepExecution step : steps) {
            for (Map<String, Object> historyEntry : step.getStateHistory()) {
                String timestamp = (String) historyEntry.get("timestamp");
                String status = (String) historyEntry.get("status");
                int attempt = ((Number) historyEntry.getOrDefault("attempt", 0)).intValue();

                String eventType = switch (status) {
                    case "RUNNING" -> "STEP_STARTED";
                    case "SUCCEEDED" -> "STEP_SUCCEEDED";
                    case "FAILED" -> "STEP_FAILED";
                    case "RETRY_SCHEDULED" -> "STEP_RETRY_SCHEDULED";
                    case "WAITING" -> "STEP_WAITING";
                    case "DEAD_LETTERED" -> "STEP_DEAD_LETTERED";
                    case "COMPENSATING" -> "STEP_COMPENSATING";
                    case "COMPENSATED" -> "STEP_COMPENSATED";
                    default -> "STEP_" + status;
                };

                Map<String, Object> detail = new LinkedHashMap<>();
                if (step.getErrorMessage() != null && "STEP_FAILED".equals(eventType)) {
                    detail.put("error", step.getErrorMessage());
                }
                if (step.getNextRetryAt() != null && "STEP_RETRY_SCHEDULED".equals(eventType)) {
                    detail.put("next_retry_at", step.getNextRetryAt().toString());
                }

                events.add(new TimelineEvent(timestamp, eventType, step.getStepName(),
                        attempt > 0 ? attempt : null, detail));
            }
        }

        // Sort by timestamp
        events.sort(Comparator.comparing(TimelineEvent::timestamp));

        return new TimelineResponse(executionId, events);
    }
}
```

### Step 9.5 — Update WorkflowExecutionController.java

Add signal, cancel, and timeline endpoints to the existing controller:

```java
// Inject additional services:
private final SignalService signalService;
private final CancelService cancelService;
private final TimelineService timelineService;

// Add constructor parameter expansion for all four services.

@PostMapping("/{id}/signal")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void signal(
        @PathVariable UUID id,
        @Valid @RequestBody SignalRequest request,
        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    signalService.signal(id, principal.tenantId(), request);
}

@PostMapping("/{id}/cancel")
public ExecutionResponse cancel(
        @PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ExecutionResponse.from(cancelService.cancel(id, principal.tenantId()));
}

@GetMapping("/{id}/timeline")
public TimelineResponse getTimeline(
        @PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return timelineService.getTimeline(id, principal.tenantId());
}
```

Full updated controller (replacing Task 5 version):

```java
package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.*;
import com.atlas.workflow.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;
    private final SignalService signalService;
    private final CancelService cancelService;
    private final TimelineService timelineService;

    public WorkflowExecutionController(WorkflowExecutionService service,
                                        SignalService signalService,
                                        CancelService cancelService,
                                        TimelineService timelineService) {
        this.service = service;
        this.signalService = signalService;
        this.cancelService = cancelService;
        this.timelineService = timelineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionResponse startExecution(
            @Valid @RequestBody StartExecutionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {
        String correlationId = httpRequest.getHeader("X-Correlation-ID");
        return ExecutionResponse.from(
                service.startExecution(request, principal.tenantId(), correlationId));
    }

    @GetMapping("/{id}")
    public ExecutionResponse getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ExecutionResponse.from(service.getById(id, principal.tenantId()));
    }

    @PostMapping("/{id}/signal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signal(
            @PathVariable UUID id,
            @Valid @RequestBody SignalRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        signalService.signal(id, principal.tenantId(), request);
    }

    @PostMapping("/{id}/cancel")
    public ExecutionResponse cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ExecutionResponse.from(cancelService.cancel(id, principal.tenantId()));
    }

    @GetMapping("/{id}/timeline")
    public TimelineResponse getTimeline(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return timelineService.getTimeline(id, principal.tenantId());
    }
}
```

### Step 9.6 — Create SignalCancelTimelineIT.java

```java
package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SignalCancelTimelineIT {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private WorkflowDefinitionRepository definitionRepository;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    @Autowired
    private StepExecutionRepository stepRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setBearerAuth("test-jwt-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private WorkflowExecution savedWaitingExecution(String stepName) {
        var def = WorkflowDefinition.create(tenantId, "wait-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", stepName, "type", "EVENT_WAIT")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-wait-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.transitionTo(ExecutionStatus.WAITING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                stepName, 0, "EVENT_WAIT", 1, null, Map.of());
        step.lease("w1");
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.WAITING);
        stepRepository.save(step);

        return execution;
    }

    @Test
    void cancelRunningExecution_returns200_withCanceledStatus() {
        var def = WorkflowDefinition.create(tenantId, "cancel-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-cancel-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("CANCELED");
    }

    @Test
    void cancelCompletedExecution_returns409() {
        var def = WorkflowDefinition.create(tenantId, "cancel-comp-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-cancel-comp-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.transitionTo(ExecutionStatus.COMPLETED);
        execution = executionRepository.save(execution);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/cancel",
                HttpMethod.POST, new HttpEntity<>(null, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void signalWaitingExecution_returns204() {
        WorkflowExecution execution = savedWaitingExecution("wait-for-ack");

        var signal = Map.of("stepName", "wait-for-ack",
                            "payload", Map.of("acknowledgedBy", "user-123"));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/signal",
                HttpMethod.POST, new HttpEntity<>(signal, headers()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void signalNonWaitingExecution_returns409() {
        var def = WorkflowDefinition.create(tenantId, "signal-fail-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-signal-fail-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        var signal = Map.of("stepName", "s1", "payload", Map.of());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/signal",
                HttpMethod.POST, new HttpEntity<>(signal, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getTimeline_returnsOrderedEvents() {
        var def = WorkflowDefinition.create(tenantId, "timeline-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-timeline-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                "s1", 0, "INTERNAL_COMMAND", 1, null, Map.of());
        step.lease("w1");
        step.transitionTo(StepStatus.RUNNING);
        stepRepository.save(step);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/timeline",
                HttpMethod.GET, new HttpEntity<>(null, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("events");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).get("type")).isEqualTo("EXECUTION_STARTED");
    }
}
```

### Step 9.7 — Run tests

```bash
mvn -pl workflow-service test -Dtest=SignalCancelTimelineIT
```

**Git commit message:** `feat(workflow-service): signal, cancel, and timeline APIs with integration tests`

---

## Task 10: Dead-Letter Handling

**Files to create:**
- `workflow-service/src/main/resources/db/migration/V006__create_dead_letter.sql`
- `workflow-service/src/main/java/com/atlas/workflow/domain/DeadLetterItem.java`
- `workflow-service/src/main/java/com/atlas/workflow/repository/DeadLetterRepository.java`
- `workflow-service/src/main/java/com/atlas/workflow/service/DeadLetterService.java`
- `workflow-service/src/main/java/com/atlas/workflow/dto/DeadLetterResponse.java`
- `workflow-service/src/main/java/com/atlas/workflow/controller/DeadLetterController.java`
- `workflow-service/src/test/java/com/atlas/workflow/controller/DeadLetterControllerIT.java`

### Step 10.1 — Create V006__create_dead_letter.sql

```sql
CREATE TABLE workflow.dead_letter_items (
    dead_letter_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    execution_id       UUID         NOT NULL REFERENCES workflow.workflow_executions(execution_id),
    step_execution_id  UUID         REFERENCES workflow.step_executions(step_execution_id),
    step_name          VARCHAR(255),
    step_type          VARCHAR(50),
    error_message      TEXT,
    error_history      JSONB        NOT NULL DEFAULT '[]',
    attempt_count      INT          NOT NULL DEFAULT 0,
    payload            JSONB        NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at        TIMESTAMPTZ,
    resolved_at        TIMESTAMPTZ
);

CREATE INDEX idx_dead_letter_tenant     ON workflow.dead_letter_items (tenant_id);
CREATE INDEX idx_dead_letter_execution  ON workflow.dead_letter_items (execution_id);
CREATE INDEX idx_dead_letter_unresolved ON workflow.dead_letter_items (tenant_id, resolved_at) WHERE resolved_at IS NULL;
```

### Step 10.2 — Create DeadLetterItem.java

```java
package com.atlas.workflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_items", schema = "workflow")
public class DeadLetterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "dead_letter_id")
    private UUID deadLetterId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "step_execution_id")
    private UUID stepExecutionId;

    @Column(name = "step_name")
    private String stepName;

    @Column(name = "step_type")
    private String stepType;

    @Column(name = "error_message")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> errorHistory;

    @Column(name = "attempt_count")
    private int attemptCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected DeadLetterItem() {}

    public static DeadLetterItem from(StepExecution step) {
        var item = new DeadLetterItem();
        item.tenantId = step.getTenantId();
        item.executionId = step.getExecutionId();
        item.stepExecutionId = step.getStepExecutionId();
        item.stepName = step.getStepName();
        item.stepType = step.getStepType();
        item.errorMessage = step.getErrorMessage();
        item.errorHistory = step.getStateHistory();
        item.attemptCount = step.getAttemptCount();
        item.payload = step.getInputJson();
        item.createdAt = Instant.now();
        return item;
    }

    public void markReplayed() { this.replayedAt = Instant.now(); }
    public void markResolved() { this.resolvedAt = Instant.now(); }

    public UUID getDeadLetterId() { return deadLetterId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getStepExecutionId() { return stepExecutionId; }
    public String getStepName() { return stepName; }
    public String getStepType() { return stepType; }
    public String getErrorMessage() { return errorMessage; }
    public List<Map<String, Object>> getErrorHistory() { return errorHistory; }
    public int getAttemptCount() { return attemptCount; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReplayedAt() { return replayedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
```

### Step 10.3 — Create DeadLetterRepository.java

```java
package com.atlas.workflow.repository;

import com.atlas.workflow.domain.DeadLetterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterItem, UUID> {

    List<DeadLetterItem> findAllByTenantIdAndResolvedAtIsNull(UUID tenantId);

    Optional<DeadLetterItem> findByDeadLetterIdAndTenantId(UUID deadLetterId, UUID tenantId);
}
```

### Step 10.4 — Create DeadLetterResponse.java

```java
package com.atlas.workflow.dto;

import com.atlas.workflow.domain.DeadLetterItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeadLetterResponse(
        UUID deadLetterId,
        UUID tenantId,
        UUID executionId,
        UUID stepExecutionId,
        String stepName,
        String stepType,
        String errorMessage,
        List<Map<String, Object>> errorHistory,
        int attemptCount,
        Map<String, Object> payload,
        Instant createdAt,
        Instant replayedAt,
        Instant resolvedAt
) {
    public static DeadLetterResponse from(DeadLetterItem item) {
        return new DeadLetterResponse(
                item.getDeadLetterId(),
                item.getTenantId(),
                item.getExecutionId(),
                item.getStepExecutionId(),
                item.getStepName(),
                item.getStepType(),
                item.getErrorMessage(),
                item.getErrorHistory(),
                item.getAttemptCount(),
                item.getPayload(),
                item.getCreatedAt(),
                item.getReplayedAt(),
                item.getResolvedAt()
        );
    }
}
```

### Step 10.5 — Create DeadLetterService.java

The replay action creates a new outbox event to re-publish the step execute command, giving the worker another chance to execute it.

```java
package com.atlas.workflow.service;

import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final StepExecutionRepository stepRepository;
    private final OutboxRepository outboxRepository;

    public DeadLetterService(DeadLetterRepository deadLetterRepository,
                              StepExecutionRepository stepRepository,
                              OutboxRepository outboxRepository) {
        this.deadLetterRepository = deadLetterRepository;
        this.stepRepository = stepRepository;
        this.outboxRepository = outboxRepository;
    }

    public List<DeadLetterItem> listUnresolved(UUID tenantId) {
        return deadLetterRepository.findAllByTenantIdAndResolvedAtIsNull(tenantId);
    }

    @Transactional
    public DeadLetterItem replay(UUID deadLetterId, UUID tenantId) {
        DeadLetterItem item = deadLetterRepository
                .findByDeadLetterIdAndTenantId(deadLetterId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dead-letter item not found: " + deadLetterId));

        if (item.getStepExecutionId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dead-letter item has no associated step execution to replay");
        }

        StepExecution step = stepRepository.findById(item.getStepExecutionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Step execution not found: " + item.getStepExecutionId()));

        // Reset step to PENDING for re-execution
        step.transitionTo(StepStatus.PENDING);
        stepRepository.save(step);

        // Re-publish the step execute command via outbox
        Map<String, Object> payload = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "tenant_id", step.getTenantId().toString(),
                "step_name", step.getStepName(),
                "step_type", step.getStepType(),
                "attempt", step.getAttemptCount(),
                "input", step.getInputJson(),
                "timeout_ms", step.getTimeoutMs() != null ? step.getTimeoutMs() : 30000,
                "is_replay", true
        );

        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution", step.getStepExecutionId().toString(),
                "step.execute", "workflow.steps.execute",
                payload, step.getTenantId());
        outboxRepository.save(outboxEvent);

        item.markReplayed();
        return deadLetterRepository.save(item);
    }
}
```

### Step 10.6 — Create DeadLetterController.java

```java
package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.DeadLetterResponse;
import com.atlas.workflow.service.DeadLetterService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dead-letter")
public class DeadLetterController {

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public List<DeadLetterResponse> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return service.listUnresolved(principal.tenantId())
                .stream()
                .map(DeadLetterResponse::from)
                .toList();
    }

    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return DeadLetterResponse.from(service.replay(id, principal.tenantId()));
    }
}
```

### Step 10.7 — Wire dead-letter creation into StepResultProcessor

In `StepResultProcessor.handleFailed`, when we dead-letter a step (after retries exhausted), also create a `DeadLetterItem`. Inject `DeadLetterRepository` into `StepResultProcessor`:

```java
// Add to StepResultProcessor constructor and field:
private final DeadLetterRepository deadLetterRepository;

// In handleFailed, after step.transitionTo(StepStatus.DEAD_LETTERED):
DeadLetterItem deadLetterItem = DeadLetterItem.from(step);
deadLetterRepository.save(deadLetterItem);
```

### Step 10.8 — Create DeadLetterControllerIT.java

```java
package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DeadLetterControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private WorkflowDefinitionRepository definitionRepository;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    @Autowired
    private StepExecutionRepository stepRepository;
    @Autowired
    private DeadLetterRepository deadLetterRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setBearerAuth("test-jwt-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private DeadLetterItem savedDeadLetterItem() {
        var def = WorkflowDefinition.create(tenantId, "dl-wf-" + UUID.randomUUID(), 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        def.publish();
        def = definitionRepository.save(def);

        var execution = WorkflowExecution.create(tenantId, def.getDefinitionId(),
                "idem-dl-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(execution.getExecutionId(), tenantId,
                "s1", 0, "INTERNAL_COMMAND", 1, null, Map.of());
        step.lease("w1");
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.FAILED);
        step.transitionTo(StepStatus.DEAD_LETTERED);
        step.setErrorMessage("simulated fatal error");
        step = stepRepository.save(step);

        DeadLetterItem item = DeadLetterItem.from(step);
        return deadLetterRepository.save(item);
    }

    @Test
    void listDeadLetter_returnsUnresolvedItems() {
        savedDeadLetterItem();

        ResponseEntity<List> response = restTemplate.exchange(
                "/api/v1/dead-letter",
                HttpMethod.GET, new HttpEntity<>(null, headers()), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void replayDeadLetter_marksReplayedAt() {
        DeadLetterItem item = savedDeadLetterItem();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dead-letter/" + item.getDeadLetterId() + "/replay",
                HttpMethod.POST, new HttpEntity<>(null, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("replayedAt")).isNotNull();
    }

    @Test
    void replayUnknownItem_returns404() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/dead-letter/" + UUID.randomUUID() + "/replay",
                HttpMethod.POST, new HttpEntity<>(null, headers()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

### Step 10.9 — Run tests

```bash
mvn -pl workflow-service test -Dtest=DeadLetterControllerIT
```

**Git commit message:** `feat(workflow-service): dead-letter handling, V006 migration, GET list and POST replay APIs`

---

## Task 11: Tenant Scoping

**Files to create:**
- `workflow-service/src/main/java/com/atlas/workflow/security/TenantContext.java`
- `workflow-service/src/main/java/com/atlas/workflow/security/TenantFilterAspect.java`
- `workflow-service/src/test/java/com/atlas/workflow/security/TenantIsolationIT.java`

### Step 11.1 — Add Hibernate filter annotations to all tenant-scoped entities

All entities with `tenant_id` must have `@FilterDef` and `@Filter` applied. Add to `WorkflowDefinition`, `WorkflowExecution`, `StepExecution`, `DeadLetterItem`, and `OutboxEvent`.

**Pattern (add to each entity class):**

```java
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@FilterDef(name = "tenantFilter",
           parameters = @ParamDef(name = "tenantId", type = java.util.UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
// ... existing @Entity, @Table annotations
public class WorkflowDefinition {
    // ... unchanged
}
```

Apply the same pattern to: `WorkflowExecution`, `StepExecution`, `DeadLetterItem`, `OutboxEvent`.

### Step 11.2 — Create TenantContext.java

```java
package com.atlas.workflow.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

@Component
@RequestScope
public class TenantContext {

    private UUID tenantId;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}
```

### Step 11.3 — Update JwtAuthenticationFilter to set TenantContext

In `JwtAuthenticationFilter`, inject `TenantContext` and set the tenant ID after parsing the JWT:

```java
// Add field and constructor injection:
private final TenantContext tenantContext;

public JwtAuthenticationFilter(JwtTokenParser jwtTokenParser, TenantContext tenantContext) {
    this.jwtTokenParser = jwtTokenParser;
    this.tenantContext = tenantContext;
}

// In doFilterInternal, after authentication is set:
if (principal != null) {
    // ... existing authentication code ...
    tenantContext.setTenantId(principal.tenantId());
}
```

### Step 11.4 — Create TenantFilterAspect.java

The aspect enables the Hibernate `tenantFilter` on all repository calls to ensure no query ever returns data from a different tenant, even if application code accidentally forgets to include `tenant_id`.

```java
package com.atlas.workflow.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    private final TenantContext tenantContext;

    public TenantFilterAspect(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Around("execution(* com.atlas.workflow.repository.*.*(..))")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        if (tenantContext.getTenantId() == null) {
            return joinPoint.proceed();
        }

        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("tenantFilter")
               .setParameter("tenantId", tenantContext.getTenantId());
        try {
            return joinPoint.proceed();
        } finally {
            session.disableFilter("tenantFilter");
        }
    }
}
```

### Step 11.5 — Create TenantIsolationIT.java

```java
package com.atlas.workflow.security;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.*;
import com.atlas.workflow.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TenantIsolationIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    /**
     * Tenant A creates a definition. Tenant B must not be able to retrieve it.
     * The test JWTs encode different tenant IDs:
     *   tenantA-token → tenant_id=00000000-0000-0000-0000-000000000001
     *   tenantB-token → tenant_id=00000000-0000-0000-0000-000000000002
     */
    @Test
    void tenantA_definition_notVisibleToTenantB() {
        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Directly persist a definition for tenant A (bypassing API to skip JWT requirement)
        var defA = WorkflowDefinition.create(tenantA, "tenant-a-wf", 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        defA.publish();
        UUID defAId = definitionRepository.save(defA).getDefinitionId();

        // Tenant B tries to GET tenant A's definition
        var headersB = new HttpHeaders();
        headersB.setBearerAuth("tenant-b-test-token"); // encodes tenant_id=tenantB
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + defAId,
                HttpMethod.GET,
                new HttpEntity<>(null, headersB),
                Map.class
        );

        // Must return 404 — definition exists but not for tenant B
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void tenantB_canCreateDefinitionWithSameNameAstenantA() {
        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Both tenants create a definition with the same name and version
        var defA = WorkflowDefinition.create(tenantA, "shared-name", 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        definitionRepository.save(defA);

        var defB = WorkflowDefinition.create(tenantB, "shared-name", 1,
                List.of(Map.of("name", "s1", "type", "INTERNAL_COMMAND")), Map.of(), "API");
        // Should not throw — unique constraint is (tenant_id, name, version)
        var saved = definitionRepository.save(defB);
        assertThat(saved.getDefinitionId()).isNotNull();
    }
}
```

### Step 11.6 — Run tests

```bash
mvn -pl workflow-service test -Dtest=TenantIsolationIT
mvn -pl workflow-service test
```

**Git commit message:** `feat(workflow-service): tenant scoping with Hibernate @Filter, TenantContext, TenantFilterAspect, cross-tenant isolation tests`

---

## Summary

| Task | Domain / Layer | Key Deliverable |
|------|---------------|-----------------|
| 1 | Scaffolding | Module compiles, context loads, Testcontainers wired |
| 2 | Definition domain | DRAFT→PUBLISHED lifecycle, REST API |
| 3 | Execution domain | ExecutionStatus enum, state machine, all transitions tested |
| 4 | Step domain | StepStatus enum, StepExecution entity, step state machine |
| 5 | Execution engine | Start workflow, outbox table, OutboxPublisher |
| 6 | Step result processing | SUCCEEDED/FAILED/DELAY/WAITING outcomes, deduplication |
| 7 | Compensation | Reverse-order compensation, COMPENSATION_FAILED terminal |
| 8 | Schedulers | Timeout, retry, outbox cleanup |
| 9 | Signal/Cancel/Timeline | POST signal, POST cancel, GET timeline |
| 10 | Dead-letter | V006 migration, inspect + replay APIs |
| 11 | Tenant scoping | Hibernate filter, TenantFilterAspect, isolation tests |

**Total tasks: 11**

**Approximate step count by task:**
- Task 1: 10 steps
- Task 2: 9 steps
- Task 3: 7 steps
- Task 4: 7 steps
- Task 5: 10 steps
- Task 6: 4 steps
- Task 7: 4 steps
- Task 8: 4 steps
- Task 9: 7 steps
- Task 10: 9 steps
- Task 11: 6 steps

**Total steps: 77**
