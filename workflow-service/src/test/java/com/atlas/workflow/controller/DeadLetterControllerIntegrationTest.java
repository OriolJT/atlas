package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.DeadLetterItem;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.dto.DeadLetterResponse;
import com.atlas.workflow.repository.DeadLetterItemRepository;
import com.atlas.workflow.repository.OutboxRepository;
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
import org.springframework.core.ParameterizedTypeReference;
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
class DeadLetterControllerIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeadLetterItemRepository deadLetterItemRepository;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);
    }

    @Test
    void list_returnsDeadLetterItemsForTenant() {
        // Create a dead-letter item directly
        StepExecution step = createPersistedStep();

        DeadLetterItem item = DeadLetterItem.create(
                tenantId,
                step.getExecutionId(),
                step.getStepExecutionId(),
                step.getStepName(),
                "Connection timeout",
                3,
                Map.of("orderId", "ABC123")
        );
        deadLetterItemRepository.save(item);

        ResponseEntity<List<DeadLetterResponse>> response = restTemplate.exchange(
                "/api/v1/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<List<DeadLetterResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<DeadLetterResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).hasSize(1);

        DeadLetterResponse returned = body.get(0);
        assertThat(returned.deadLetterId()).isEqualTo(item.getDeadLetterId());
        assertThat(returned.tenantId()).isEqualTo(tenantId);
        assertThat(returned.executionId()).isEqualTo(step.getExecutionId());
        assertThat(returned.stepExecutionId()).isEqualTo(step.getStepExecutionId());
        assertThat(returned.stepName()).isEqualTo(step.getStepName());
        assertThat(returned.errorMessage()).isEqualTo("Connection timeout");
        assertThat(returned.attemptCount()).isEqualTo(3);
        assertThat(returned.replayed()).isFalse();
    }

    @Test
    void replay_marksItemReplayedAndCreatesOutboxEvent() {
        StepExecution step = createPersistedDeadLetteredStep();

        DeadLetterItem item = DeadLetterItem.create(
                tenantId,
                step.getExecutionId(),
                step.getStepExecutionId(),
                step.getStepName(),
                "Service unavailable",
                3,
                Map.of("orderId", "REPLAY_TEST")
        );
        deadLetterItemRepository.save(item);

        long outboxCountBefore = outboxRepository.count();

        ResponseEntity<DeadLetterResponse> response = restTemplate.exchange(
                "/api/v1/dead-letter/" + item.getDeadLetterId() + "/replay",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                DeadLetterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeadLetterResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.replayed()).isTrue();
        assertThat(body.replayedAt()).isNotNull();

        // Verify the item is persisted as replayed
        DeadLetterItem persisted = deadLetterItemRepository
                .findByDeadLetterIdAndTenantId(item.getDeadLetterId(), tenantId)
                .orElseThrow();
        assertThat(persisted.isReplayed()).isTrue();
        assertThat(persisted.getReplayedAt()).isNotNull();

        // Verify the step was reset to PENDING
        StepExecution updatedStep = stepExecutionRepository.findById(step.getStepExecutionId())
                .orElseThrow();
        assertThat(updatedStep.getStatus()).isEqualTo(StepStatus.PENDING);

        // Verify an outbox event was created for step.execute
        long outboxCountAfter = outboxRepository.count();
        assertThat(outboxCountAfter).isGreaterThan(outboxCountBefore);
    }

    private StepExecution createPersistedStep() {
        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId,
                "dl-test-workflow-" + UUID.randomUUID(),
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL")),
                Map.of(),
                "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);

        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                definition.getDefinitionId(),
                "dl-idem-" + UUID.randomUUID(),
                Map.of("orderId", "ABC123"),
                null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId,
                "step1", 0, "HTTP_CALL", 3, null, Map.of("orderId", "ABC123")
        );
        return stepExecutionRepository.save(step);
    }

    private StepExecution createPersistedDeadLetteredStep() {
        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId,
                "dl-replay-workflow-" + UUID.randomUUID(),
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL")),
                Map.of(),
                "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);

        WorkflowExecution execution = WorkflowExecution.create(
                tenantId,
                definition.getDefinitionId(),
                "dl-replay-idem-" + UUID.randomUUID(),
                Map.of("orderId", "REPLAY_TEST"),
                null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId,
                "step1", 0, "HTTP_CALL", 3, null, Map.of("orderId", "REPLAY_TEST")
        );
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.FAILED);
        step.transitionTo(StepStatus.DEAD_LETTERED);
        return stepExecutionRepository.save(step);
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
