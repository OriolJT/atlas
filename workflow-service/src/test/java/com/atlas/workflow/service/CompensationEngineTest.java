package com.atlas.workflow.service;

import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CompensationEngine#handleCompensationStepResult},
 * verifying the best-effort compensation strategy: the engine continues
 * dispatching remaining compensation steps even when one fails.
 */
@ExtendWith(MockitoExtension.class)
class CompensationEngineTest {

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private StepExecutionRepository stepExecutionRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxCaptor;

    @Captor
    private ArgumentCaptor<WorkflowExecution> executionCaptor;

    private CompensationEngine compensationEngine;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        // WorkflowDefinitionRepository is not used by handleCompensationStepResult
        compensationEngine = new CompensationEngine(
                executionRepository, null, stepExecutionRepository, outboxRepository);
        tenantId = UUID.randomUUID();
    }

    @Test
    void whenFirstCompensationFails_remainingCompensationStepsAreStillDispatched() {
        WorkflowExecution execution = createCompensatingExecution();
        UUID execId = execution.getExecutionId();

        // comp-0 is about to fail; comp-1 is still PENDING
        // The step is in COMPENSATING status so COMPENSATION_FAILED transition is valid.
        StepExecution failingComp = createCompensationStepInCompensating(execId, "compensate-step2", 0, "step2");
        StepExecution pendingComp = createCompensationStep(execId, "compensate-step1", 1, "step1");

        // After failingComp transitions to DEAD_LETTERED: allCompensationStepsDone sees
        // comp-0=DEAD_LETTERED, comp-1=PENDING -> not all done -> dispatch next
        when(stepExecutionRepository.findByExecutionIdOrderByStepIndex(execId))
                .thenReturn(List.of(failingComp, pendingComp));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        compensationEngine.handleCompensationStepResult(failingComp, execution, false, "service unavailable");

        // Failed step reaches DEAD_LETTERED
        assertThat(failingComp.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);
        assertThat(failingComp.getErrorMessage()).isEqualTo("service unavailable");

        // Next pending compensation step is dispatched via outbox
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent dispatched = outboxCaptor.getValue();
        assertThat(dispatched.getAggregateId()).isEqualTo(pendingComp.getStepExecutionId());
        assertThat(dispatched.getEventType()).isEqualTo("step.execute");

        // Execution is NOT yet finalized (more steps remain)
        verify(executionRepository, never()).save(any());
    }

    @Test
    void whenAllCompensationStepsSucceed_executionTransitionsToCompensated() {
        WorkflowExecution execution = createCompensatingExecution();
        UUID execId = execution.getExecutionId();

        // Single compensation step succeeding
        StepExecution comp = createRunningCompensationStep(execId, "compensate-step1", 0, "step1");

        // Original step in COMPENSATING so it can be marked COMPENSATED
        StepExecution originalStep = createOriginalStepInCompensating(execId, "step1", 0);

        when(stepExecutionRepository.findByExecutionIdOrderByStepIndex(execId))
                .thenReturn(List.of(originalStep, comp));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        compensationEngine.handleCompensationStepResult(comp, execution, true, null);

        assertThat(comp.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(originalStep.getStatus()).isEqualTo(StepStatus.COMPENSATED);

        verify(executionRepository).save(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getStatus()).isEqualTo(ExecutionStatus.COMPENSATED);
    }

    @Test
    void whenSomeCompensationStepsFail_executionTransitionsToCompensationFailed() {
        WorkflowExecution execution = createCompensatingExecution();
        UUID execId = execution.getExecutionId();

        // comp-0 already succeeded; comp-1 is about to fail
        StepExecution succeededComp = createSucceededCompensationStep(execId, "compensate-step2", 0, "step2");
        StepExecution failingComp = createCompensationStepInCompensating(execId, "compensate-step1", 1, "step1");

        // After failingComp -> DEAD_LETTERED: all done (SUCCEEDED + DEAD_LETTERED),
        // anyCompensationStepFailed sees DEAD_LETTERED -> true -> COMPENSATION_FAILED
        when(stepExecutionRepository.findByExecutionIdOrderByStepIndex(execId))
                .thenReturn(List.of(succeededComp, failingComp));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        compensationEngine.handleCompensationStepResult(failingComp, execution, false, "timeout");

        assertThat(failingComp.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);

        verify(executionRepository).save(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getStatus()).isEqualTo(ExecutionStatus.COMPENSATION_FAILED);
    }

    // --- helpers ---

    private WorkflowExecution createCompensatingExecution() {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, UUID.randomUUID(), "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.fail("step failed");
        execution.transitionTo(ExecutionStatus.COMPENSATING);
        return execution;
    }

    /** Creates a compensation step in PENDING status (default after creation). */
    private StepExecution createCompensationStep(UUID execId, String name, int index,
                                                  String compensationFor) {
        return StepExecution.createCompensation(
                execId, tenantId, name, index, "HTTP_CALL_UNDO", Map.of(), compensationFor);
    }

    /** Creates a compensation step transitioned to RUNNING. */
    private StepExecution createRunningCompensationStep(UUID execId, String name, int index,
                                                         String compensationFor) {
        StepExecution step = createCompensationStep(execId, name, index, compensationFor);
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        return step;
    }

    /**
     * Creates a compensation step in COMPENSATING status.
     * The failure path in CompensationEngine transitions the step through
     * COMPENSATION_FAILED -> DEAD_LETTERED. The state machine allows
     * COMPENSATING -> COMPENSATION_FAILED -> DEAD_LETTERED.
     */
    private StepExecution createCompensationStepInCompensating(UUID execId, String name, int index,
                                                                String compensationFor) {
        StepExecution step = createRunningCompensationStep(execId, name, index, compensationFor);
        step.transitionTo(StepStatus.SUCCEEDED);
        step.transitionTo(StepStatus.COMPENSATING);
        return step;
    }

    /** Creates a compensation step that has already SUCCEEDED. */
    private StepExecution createSucceededCompensationStep(UUID execId, String name, int index,
                                                           String compensationFor) {
        StepExecution step = createRunningCompensationStep(execId, name, index, compensationFor);
        step.transitionTo(StepStatus.SUCCEEDED);
        return step;
    }

    /** Creates an original (non-compensation) step in COMPENSATING status. */
    private StepExecution createOriginalStepInCompensating(UUID execId, String name, int index) {
        StepExecution step = StepExecution.create(
                execId, tenantId, name, index, "HTTP_CALL", 3, null, Map.of());
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.SUCCEEDED);
        step.transitionTo(StepStatus.COMPENSATING);
        return step;
    }
}
