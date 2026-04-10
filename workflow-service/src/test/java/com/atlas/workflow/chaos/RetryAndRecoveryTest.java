package com.atlas.workflow.chaos;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.DeadLetterItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RetryAndRecoveryTest {

    @Autowired
    private ChaosTestHelper helper;

    @Autowired
    private DeadLetterItemRepository deadLetterItemRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void transientFailure_retriesAndCompletes() {
        // 1-step definition with max_attempts=3
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "retry-test-" + UUID.randomUUID(),
                List.of(Map.of(
                        "name", "flaky-step",
                        "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 3)
                )),
                Map.of()
        );

        // Start execution -- creates first step in PENDING
        WorkflowExecution execution = helper.startExecution(tenantId, definition.getDefinitionId(), Map.of());
        UUID executionId = execution.getExecutionId();

        // Attempt 1: get pending step, simulate failure (retryable)
        StepExecution step = helper.getNextPendingStep(executionId);
        assertThat(step).isNotNull();
        helper.simulateWorkerFailure(step, "Connection timeout", false);

        // After failure, step should be RETRY_SCHEDULED (attemptCount=1 < maxAttempts=3)
        step = helper.refreshStep(step.getStepExecutionId());
        assertThat(step.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        assertThat(step.getAttemptCount()).isEqualTo(1);

        // Simulate RetryScheduler: transition back to PENDING
        step = helper.transitionRetryToPending(step);
        assertThat(step.getStatus()).isEqualTo(StepStatus.PENDING);

        // Attempt 2: simulate failure again
        helper.simulateWorkerFailure(step, "Connection timeout again", false);
        step = helper.refreshStep(step.getStepExecutionId());
        assertThat(step.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        assertThat(step.getAttemptCount()).isEqualTo(2);

        // Simulate RetryScheduler again
        step = helper.transitionRetryToPending(step);

        // Attempt 3: simulate success
        helper.simulateWorkerSuccess(step, Map.of("result", "finally worked"));
        step = helper.refreshStep(step.getStepExecutionId());
        assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step.getAttemptCount()).isEqualTo(3);

        // Execution should be COMPLETED
        WorkflowExecution updated = helper.refreshExecution(executionId);
        assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // Verify state history contains RETRY_SCHEDULED entries
        long retryScheduledCount = step.getStateHistory().stream()
                .filter(h -> "RETRY_SCHEDULED".equals(h.get("status")))
                .count();
        assertThat(retryScheduledCount).isEqualTo(2);
    }

    @Test
    void permanentFailure_deadLettersStep() {
        // 1-step definition with max_attempts=2
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "dead-letter-test-" + UUID.randomUUID(),
                List.of(Map.of(
                        "name", "doomed-step",
                        "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 2)
                )),
                Map.of()
        );

        WorkflowExecution execution = helper.startExecution(tenantId, definition.getDefinitionId(), Map.of());
        UUID executionId = execution.getExecutionId();

        // Attempt 1: fail (retryable) -- attemptCount will be 1, maxAttempts is 2 -> retry scheduled
        StepExecution step = helper.getNextPendingStep(executionId);
        assertThat(step).isNotNull();
        helper.simulateWorkerFailure(step, "Temporary error", false);

        step = helper.refreshStep(step.getStepExecutionId());
        assertThat(step.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);

        // Simulate RetryScheduler
        step = helper.transitionRetryToPending(step);

        // Attempt 2: fail again -- attemptCount will be 2, equals maxAttempts -> dead-lettered
        helper.simulateWorkerFailure(step, "Still failing", false);
        step = helper.refreshStep(step.getStepExecutionId());
        assertThat(step.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);

        // Execution should be FAILED or COMPENSATING
        WorkflowExecution updated = helper.refreshExecution(executionId);
        assertThat(updated.getStatus()).isIn(ExecutionStatus.FAILED, ExecutionStatus.COMPENSATING,
                ExecutionStatus.COMPENSATED);

        // Verify dead-letter item was created
        var deadLetters = deadLetterItemRepository.findByTenantIdAndReplayedFalse(tenantId);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).getStepExecutionId()).isEqualTo(step.getStepExecutionId());
        assertThat(deadLetters.get(0).getErrorMessage()).isEqualTo("Still failing");
    }
}
