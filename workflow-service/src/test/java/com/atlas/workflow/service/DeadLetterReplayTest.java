package com.atlas.workflow.service;

import com.atlas.workflow.domain.DeadLetterItem;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.DeadLetterItemRepository;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeadLetterService#replay}, verifying that replay
 * resets the step's attempt count, transitions states correctly, and creates
 * an outbox event for re-dispatch.
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterReplayTest {

    @Mock
    private DeadLetterItemRepository deadLetterItemRepository;

    @Mock
    private StepExecutionRepository stepExecutionRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxCaptor;

    private DeadLetterService deadLetterService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        deadLetterService = new DeadLetterService(
                deadLetterItemRepository, stepExecutionRepository,
                outboxRepository, executionRepository);
        tenantId = UUID.randomUUID();
    }

    @Test
    void replay_resetsAttemptCountToZero() {
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.FAILED);
        setupMocks(fixture);

        deadLetterService.replay(fixture.deadLetterItem.getDeadLetterId(), tenantId);

        assertThat(fixture.step.getAttemptCount()).isZero();
    }

    @Test
    void replay_transitionsStepToPending() {
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.FAILED);
        setupMocks(fixture);

        deadLetterService.replay(fixture.deadLetterItem.getDeadLetterId(), tenantId);

        assertThat(fixture.step.getStatus()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void replay_transitionsExecutionFromFailedToRunning() {
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.FAILED);
        setupMocks(fixture);

        deadLetterService.replay(fixture.deadLetterItem.getDeadLetterId(), tenantId);

        assertThat(fixture.execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void replay_createsOutboxEventForRedispatch() {
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.FAILED);
        setupMocks(fixture);

        deadLetterService.replay(fixture.deadLetterItem.getDeadLetterId(), tenantId);

        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent outbox = outboxCaptor.getValue();

        assertThat(outbox.getAggregateId()).isEqualTo(fixture.step.getStepExecutionId());
        assertThat(outbox.getEventType()).isEqualTo("step.execute");
        assertThat(outbox.getTopic()).isEqualTo("workflow.steps.execute");
        assertThat(outbox.getAggregateType()).isEqualTo("StepExecution");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = outbox.getPayload();
        assertThat(payload).containsEntry("step_execution_id", fixture.step.getStepExecutionId().toString());
        assertThat(payload).containsEntry("step_name", fixture.step.getStepName());
    }

    @Test
    void replay_marksDeadLetterItemAsReplayed() {
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.FAILED);
        setupMocks(fixture);

        DeadLetterItem result = deadLetterService.replay(
                fixture.deadLetterItem.getDeadLetterId(), tenantId);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getReplayedAt()).isNotNull();
    }

    @Test
    void replay_doesNotTransitionExecutionIfNotFailed() {
        // Execution in COMPENSATING status -- replay should NOT change it to RUNNING
        ReplayFixture fixture = createReplayFixture(ExecutionStatus.COMPENSATING);
        setupMocks(fixture, false);

        deadLetterService.replay(fixture.deadLetterItem.getDeadLetterId(), tenantId);

        // Execution stays in COMPENSATING (the code only transitions FAILED -> RUNNING)
        assertThat(fixture.execution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        // Step is still reset
        assertThat(fixture.step.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(fixture.step.getAttemptCount()).isZero();
    }

    // --- helpers ---

    private record ReplayFixture(DeadLetterItem deadLetterItem, StepExecution step,
                                  WorkflowExecution execution) {}

    private ReplayFixture createReplayFixture(ExecutionStatus executionStatus) {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, UUID.randomUUID(), "idem-" + UUID.randomUUID(), Map.of(), null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        if (executionStatus == ExecutionStatus.FAILED) {
            execution.fail("step failed");
        } else if (executionStatus == ExecutionStatus.COMPENSATING) {
            execution.fail("step failed");
            execution.transitionTo(ExecutionStatus.COMPENSATING);
        }

        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, "step1", 0,
                "HTTP_CALL", 3, null, Map.of("key", "value"));
        // Simulate a step that ran, failed, and was dead-lettered
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.FAILED);
        step.transitionTo(StepStatus.DEAD_LETTERED);

        DeadLetterItem item = DeadLetterItem.create(
                tenantId, execution.getExecutionId(), step.getStepExecutionId(),
                "step1", "original error", step.getAttemptCount(), step.getInputJson());

        return new ReplayFixture(item, step, execution);
    }

    private void setupMocks(ReplayFixture fixture) {
        setupMocks(fixture, true);
    }

    private void setupMocks(ReplayFixture fixture, boolean expectExecutionSave) {
        when(deadLetterItemRepository.findByDeadLetterIdAndTenantId(
                fixture.deadLetterItem.getDeadLetterId(), tenantId))
                .thenReturn(Optional.of(fixture.deadLetterItem));
        when(deadLetterItemRepository.save(any(DeadLetterItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(stepExecutionRepository.findById(fixture.step.getStepExecutionId()))
                .thenReturn(Optional.of(fixture.step));
        when(stepExecutionRepository.save(any(StepExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionRepository.findById(fixture.step.getExecutionId()))
                .thenReturn(Optional.of(fixture.execution));
        if (expectExecutionSave) {
            when(executionRepository.save(any(WorkflowExecution.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
        }
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }
}
