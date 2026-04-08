package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.dto.ExecutionResponse;
import com.atlas.workflow.dto.TimelineResponse;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
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
class WorkflowExecutionApiIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    private UUID tenantId;
    private String authToken;
    private WorkflowDefinition publishedDefinition;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);

        publishedDefinition = WorkflowDefinition.create(
                tenantId,
                "signal-cancel-test-workflow-" + UUID.randomUUID(),
                1,
                Map.of("step1", Map.of("type", "EVENT_WAIT")),
                Map.of(),
                "EVENT"
        );
        publishedDefinition.publish();
        publishedDefinition = definitionRepository.save(publishedDefinition);
    }

    @Test
    void cancel_activeExecution_returns200WithCanceledStatus() {
        // Create a RUNNING execution directly
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                publishedDefinition.getDefinitionId(),
                "cancel-test-" + UUID.randomUUID(),
                Map.of("key", "value"),
                null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        ResponseEntity<ExecutionResponse> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                ExecutionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecutionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(ExecutionStatus.CANCELED);
    }

    @Test
    void cancel_completedExecution_returns409() {
        // Create a COMPLETED execution directly
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                publishedDefinition.getDefinitionId(),
                "cancel-completed-" + UUID.randomUUID(),
                Map.of(),
                null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.transitionTo(ExecutionStatus.COMPLETED);
        execution = executionRepository.save(execution);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void timeline_returnsOrderedEvents() {
        // Create a RUNNING execution directly
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                publishedDefinition.getDefinitionId(),
                "timeline-test-" + UUID.randomUUID(),
                Map.of("orderId", "abc"),
                null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        // Create two step executions with state history
        StepExecution step1 = StepExecution.create(
                execution.getExecutionId(), tenantId,
                "step1", 0, "HTTP_CALL", 3, null, Map.of("orderId", "abc")
        );
        step1.transitionTo(StepStatus.LEASED);
        step1.transitionTo(StepStatus.RUNNING);
        step1.transitionTo(StepStatus.SUCCEEDED);
        step1 = stepExecutionRepository.save(step1);

        StepExecution step2 = StepExecution.create(
                execution.getExecutionId(), tenantId,
                "step2", 1, "HTTP_CALL", 3, null, Map.of()
        );
        step2.transitionTo(StepStatus.LEASED);
        step2.transitionTo(StepStatus.RUNNING);
        step2 = stepExecutionRepository.save(step2);

        ResponseEntity<TimelineResponse> response = restTemplate.exchange(
                "/api/v1/workflow-executions/" + execution.getExecutionId() + "/timeline",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                TimelineResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TimelineResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionId()).isEqualTo(execution.getExecutionId());
        assertThat(body.status()).isEqualTo(ExecutionStatus.RUNNING.name());
        assertThat(body.events()).isNotEmpty();

        // Verify events are ordered by timestamp
        List<TimelineResponse.TimelineEvent> events = body.events();
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).timestamp())
                    .isGreaterThanOrEqualTo(events.get(i - 1).timestamp());
        }

        // step1 and step2 events should both be present
        long step1Events = events.stream()
                .filter(e -> "step1".equals(e.stepName()))
                .count();
        long step2Events = events.stream()
                .filter(e -> "step2".equals(e.stepName()))
                .count();
        assertThat(step1Events).isGreaterThan(0);
        assertThat(step2Events).isGreaterThan(0);
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
