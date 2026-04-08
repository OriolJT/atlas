package com.atlas.workflow.scheduler;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RetrySchedulerIntegrationTest {

    @Autowired
    private RetryScheduler retryScheduler;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    private UUID tenantId;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        Map<String, Object> stepsJson = Map.of(
                "step1", Map.of("type", "HTTP_CALL", "maxAttempts", 3)
        );

        definition = WorkflowDefinition.create(
                tenantId, "retry-test-workflow-" + UUID.randomUUID(), 1,
                stepsJson, Map.of(), "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);
    }

    @Test
    void retryDueSteps_transitionsTosPendingAndCreatesOutboxEvent() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStepInRetryScheduled(execution, Instant.now().minusSeconds(60));

        retryScheduler.retryDueSteps();

        StepExecution updated = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.PENDING);

        List<OutboxEvent> events = outboxRepository.findUnpublished();
        assertThat(events).anyMatch(e ->
                e.getAggregateId().equals(step.getStepExecutionId())
                        && e.getEventType().equals("step.execute"));
    }

    @Test
    void retryDueSteps_doesNotPickUpFutureRetries() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStepInRetryScheduled(execution, Instant.now().plusSeconds(300));

        retryScheduler.retryDueSteps();

        StepExecution updated = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
    }

    private WorkflowExecution createRunningExecution() {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of("initial", "input"), null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        return executionRepository.save(execution);
    }

    private StepExecution createStepInRetryScheduled(WorkflowExecution execution, Instant nextRetryAt) {
        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, "step1",
                0, "HTTP_CALL", 3, null, execution.getInputJson()
        );
        // Drive through valid transitions: PENDING -> LEASED -> RUNNING -> FAILED -> RETRY_SCHEDULED
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.FAILED);
        step.scheduleRetry(nextRetryAt);
        return stepExecutionRepository.save(step);
    }
}
