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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DelaySchedulerIntegrationTest {

    @Autowired
    private DelayScheduler delayScheduler;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private UUID tenantId;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() {
        // Clean Redis delay queue before each test
        redisTemplate.delete(DelayScheduler.DELAY_QUEUE_KEY);

        tenantId = UUID.randomUUID();

        Map<String, Object> stepsJson = Map.of(
                "step1", Map.of("type", "HTTP_CALL", "maxAttempts", 3)
        );

        definition = WorkflowDefinition.create(
                tenantId, "delay-test-workflow-" + UUID.randomUUID(), 1,
                stepsJson, Map.of(), "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);
    }

    @Test
    void pollDelayQueue_picksUpDueStepAndTransitionsToPending() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStepInRetryScheduled(execution);

        // Schedule step with a wake-up time in the past (immediately due)
        delayScheduler.schedule(step.getStepExecutionId(), Instant.now().minusMillis(100).toEpochMilli());

        // Poll the delay queue
        delayScheduler.pollDelayQueue();

        // Verify step transitioned to PENDING
        StepExecution updated = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.PENDING);

        // Verify outbox event was created
        List<OutboxEvent> events = outboxRepository.findUnpublished();
        assertThat(events).anyMatch(e ->
                e.getAggregateId().equals(step.getStepExecutionId())
                        && e.getEventType().equals("step.execute"));

        // Verify step was removed from Redis
        Long queueSize = redisTemplate.opsForZSet().size(DelayScheduler.DELAY_QUEUE_KEY);
        assertThat(queueSize).isEqualTo(0);
    }

    @Test
    void pollDelayQueue_doesNotPickUpFutureSteps() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStepInRetryScheduled(execution);

        // Schedule step with a wake-up time far in the future
        delayScheduler.schedule(step.getStepExecutionId(), Instant.now().plusSeconds(300).toEpochMilli());

        delayScheduler.pollDelayQueue();

        // Step should remain in RETRY_SCHEDULED
        StepExecution updated = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);

        // Entry should still be in Redis
        Long queueSize = redisTemplate.opsForZSet().size(DelayScheduler.DELAY_QUEUE_KEY);
        assertThat(queueSize).isEqualTo(1);
    }

    @Test
    void pollDelayQueue_shortDelay_pickedUpAfterWait() throws InterruptedException {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStepInRetryScheduled(execution);

        // Schedule step with a 100ms delay
        delayScheduler.schedule(step.getStepExecutionId(), Instant.now().plusMillis(100).toEpochMilli());

        // First poll — too early
        delayScheduler.pollDelayQueue();
        StepExecution check = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(check.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);

        // Wait for delay to pass
        Thread.sleep(150);

        // Second poll — should pick it up
        delayScheduler.pollDelayQueue();
        StepExecution updated = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(StepStatus.PENDING);
    }

    private WorkflowExecution createRunningExecution() {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of("initial", "input"), null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        return executionRepository.save(execution);
    }

    private StepExecution createStepInRetryScheduled(WorkflowExecution execution) {
        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, "step1",
                0, "HTTP_CALL", 3, null, execution.getInputJson()
        );
        // Drive through valid transitions: PENDING -> LEASED -> RUNNING -> FAILED -> RETRY_SCHEDULED
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.FAILED);
        step.scheduleRetry(Instant.now());
        return stepExecutionRepository.save(step);
    }
}
