package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.dto.ExecutionResponse;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkflowExecutionControllerIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    private UUID tenantId;
    private String authToken;
    private WorkflowDefinition publishedDefinition;
    private WorkflowDefinition draftDefinition;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);

        // Create a PUBLISHED definition directly via repository
        publishedDefinition = WorkflowDefinition.create(
                tenantId,
                "published-workflow-" + UUID.randomUUID(),
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL", "url", "https://example.com")),
                Map.of(),
                "MANUAL"
        );
        publishedDefinition.publish();
        publishedDefinition = definitionRepository.save(publishedDefinition);

        // Create a DRAFT definition directly via repository
        draftDefinition = WorkflowDefinition.create(
                tenantId,
                "draft-workflow-" + UUID.randomUUID(),
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL")),
                Map.of(),
                "MANUAL"
        );
        draftDefinition = definitionRepository.save(draftDefinition);
    }

    @Test
    void startExecution_withPublishedDefinition_returns201Running() {
        var request = new StartExecutionRequest(
                publishedDefinition.getDefinitionId(),
                "idempotency-" + UUID.randomUUID(),
                Map.of("orderId", "12345")
        );

        ResponseEntity<ExecutionResponse> response = restTemplate.exchange(
                "/api/v1/workflow-executions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ExecutionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ExecutionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionId()).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
        assertThat(body.definitionId()).isEqualTo(publishedDefinition.getDefinitionId());
        assertThat(body.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.startedAt()).isNotNull();
    }

    @Test
    void startExecution_idempotent_returnsSameExecutionId() {
        String idempotencyKey = "idempotency-" + UUID.randomUUID();
        var request = new StartExecutionRequest(
                publishedDefinition.getDefinitionId(),
                idempotencyKey,
                Map.of("orderId", "12345")
        );

        // First call
        ResponseEntity<ExecutionResponse> first = restTemplate.exchange(
                "/api/v1/workflow-executions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ExecutionResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second call with same idempotency key
        ResponseEntity<ExecutionResponse> second = restTemplate.exchange(
                "/api/v1/workflow-executions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ExecutionResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(first.getBody().executionId())
                .isEqualTo(second.getBody().executionId());
    }

    @Test
    void startExecution_withDraftDefinition_returns422() {
        var request = new StartExecutionRequest(
                draftDefinition.getDefinitionId(),
                "idempotency-" + UUID.randomUUID(),
                Map.of()
        );

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/workflow-executions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void getById_unknownExecution_returns404() {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + unknownId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return headers;
    }

    private String generateTestToken(UUID tenantId) {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of("TENANT_ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
