package com.atlas.workflow.service;

import com.atlas.workflow.domain.DeadLetterItem;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.DeadLetterItemRepository;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.scheduler.DelayScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code non_retryable} flag in {@link StepResultProcessor#process}.
 * When a step result has {@code non_retryable: true}, retries must be skipped
 * even if attemptCount &lt; maxAttempts.
 */
@ExtendWith(MockitoExtension.class)
class StepResultProcessorNonRetryableTest {

    @Mock
    private StepExecutionRepository stepExecutionRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowDefinitionRepository definitionRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private CompensationEngine compensationEngine;

    @Mock
    private DeadLetterItemRepository deadLetterItemRepository;

    @Mock
    private DelayScheduler delayScheduler;

    private StepResultProcessor processor;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        processor = new StepResultProcessor(
                stepExecutionRepository, executionRepository, definitionRepository,
                outboxRepository, compensationEngine, deadLetterItemRepository, delayScheduler);
        tenantId = UUID.randomUUID();
    }

    @Test
    void nonRetryableTrue_skipsRetryAndDeadLetters_evenWhenAttemptsRemain() {
        WorkflowExecution execution = createRunningExecution();
        // maxAttempts=3, attemptCount=1 after RUNNING transition -> retries would normally remain
        StepExecution step = createRunningStep(execution, "step1", 0, 3);

        when(stepExecutionRepository.findById(step.getStepExecutionId()))
                .thenReturn(Optional.of(step));
        when(executionRepository.findById(execution.getExecutionId()))
                .thenReturn(Optional.of(execution));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(deadLetterItemRepository.save(any(DeadLetterItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = new HashMap<>();
        result.put("step_execution_id", step.getStepExecutionId().toString());
        result.put("outcome", "FAILED");
        result.put("attempt", step.getAttemptCount());
        result.put("error", "validation error");
        result.put("output", null);
        result.put("non_retryable", true);

        processor.process(result);

        // Step should be DEAD_LETTERED (skipping RETRY_SCHEDULED)
        assertThat(step.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);
        assertThat(step.getErrorMessage()).isEqualTo("validation error");

        // A dead-letter item should be created
        verify(deadLetterItemRepository).save(any(DeadLetterItem.class));

        // Compensation should be triggered
        verify(compensationEngine).startCompensation(any(WorkflowExecution.class));

        // Execution should be marked FAILED
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void nonRetryableFalse_withAttemptsRemaining_schedulesRetry() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createRunningStep(execution, "step1", 0, 3);

        when(stepExecutionRepository.findById(step.getStepExecutionId()))
                .thenReturn(Optional.of(step));
        when(executionRepository.findById(execution.getExecutionId()))
                .thenReturn(Optional.of(execution));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = new HashMap<>();
        result.put("step_execution_id", step.getStepExecutionId().toString());
        result.put("outcome", "FAILED");
        result.put("attempt", step.getAttemptCount());
        result.put("error", "transient network error");
        result.put("output", null);
        result.put("non_retryable", false);

        processor.process(result);

        // Step should be RETRY_SCHEDULED (not DEAD_LETTERED)
        assertThat(step.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        assertThat(step.getNextRetryAt()).isNotNull();

        // No dead-letter or compensation
        verify(deadLetterItemRepository, never()).save(any());
        verify(compensationEngine, never()).startCompensation(any());
    }

    @Test
    void nonRetryableAbsent_withAttemptsRemaining_schedulesRetry() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createRunningStep(execution, "step1", 0, 3);

        when(stepExecutionRepository.findById(step.getStepExecutionId()))
                .thenReturn(Optional.of(step));
        when(executionRepository.findById(execution.getExecutionId()))
                .thenReturn(Optional.of(execution));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // non_retryable key is intentionally absent from the payload
        Map<String, Object> result = new HashMap<>();
        result.put("step_execution_id", step.getStepExecutionId().toString());
        result.put("outcome", "FAILED");
        result.put("attempt", step.getAttemptCount());
        result.put("error", "transient error");
        result.put("output", null);

        processor.process(result);

        assertThat(step.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        verify(deadLetterItemRepository, never()).save(any());
        verify(compensationEngine, never()).startCompensation(any());
    }

    // --- helpers ---

    private WorkflowExecution createRunningExecution() {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, UUID.randomUUID(), "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        return execution;
    }

    private StepExecution createRunningStep(WorkflowExecution execution, String name,
                                             int index, int maxAttempts) {
        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, name, index,
                "HTTP_CALL", maxAttempts, null, Map.of());
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        return step;
    }
}
