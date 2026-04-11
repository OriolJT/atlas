package com.atlas.workflow.service;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CompensationEngineIntegrationTest {

    @Autowired
    private CompensationEngine compensationEngine;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void startCompensation_withCompensatableSteps_createsCompensationStepsAndOutbox() {
        // Definition with 2 steps and compensations for both
        Map<String, Object> stepsJson = new LinkedHashMap<>();
        stepsJson.put("step1", Map.of("type", "HTTP_CALL", "maxAttempts", 3));
        stepsJson.put("step2", Map.of("type", "HTTP_CALL", "maxAttempts", 2));

        Map<String, Object> compensationsJson = new LinkedHashMap<>();
        compensationsJson.put("step1", Map.of("type", "HTTP_CALL_UNDO"));
        compensationsJson.put("step2", Map.of("type", "HTTP_CALL_UNDO"));

        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId, "comp-test-" + UUID.randomUUID(), 1,
                stepsJson, compensationsJson, "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);

        // Create a FAILED execution with 2 SUCCEEDED steps
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.fail("Step 3 failed");
        execution = executionRepository.save(execution);

        StepExecution step1 = createSucceededStep(execution, "step1", 0, Map.of("output1", "val1"));
        StepExecution step2 = createSucceededStep(execution, "step2", 1, Map.of("output2", "val2"));

        compensationEngine.startCompensation(execution);

        // Verify execution is COMPENSATING
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        // Verify 2 compensation step executions were created
        List<StepExecution> allSteps = stepExecutionRepository
                .findByExecutionIdOrderByStepIndex(execution.getExecutionId());
        List<StepExecution> compensationSteps = allSteps.stream()
                .filter(StepExecution::isCompensation)
                .toList();
        assertThat(compensationSteps).hasSize(2);

        // Verify compensation steps reference the original steps
        assertThat(compensationSteps).extracting(StepExecution::getCompensationFor)
                .containsExactlyInAnyOrder("step2", "step1");

        // Verify compensation steps are in PENDING status
        assertThat(compensationSteps).allMatch(s -> s.getStatus() == StepStatus.PENDING);

        // Verify original steps are now in COMPENSATING
        StepExecution updatedStep1 = stepExecutionRepository.findById(step1.getStepExecutionId()).orElseThrow();
        StepExecution updatedStep2 = stepExecutionRepository.findById(step2.getStepExecutionId()).orElseThrow();
        assertThat(updatedStep1.getStatus()).isEqualTo(StepStatus.COMPENSATING);
        assertThat(updatedStep2.getStatus()).isEqualTo(StepStatus.COMPENSATING);

        // Verify only 1 outbox event: compensation executes sequentially, only the first
        // compensation step (step2, reversed order) is dispatched immediately.
        // Subsequent steps are dispatched as each one completes.
        List<OutboxEvent> outboxEvents = outboxRepository.findUnpublished();
        List<OutboxEvent> compensationOutbox = outboxEvents.stream()
                .filter(e -> compensationSteps.stream()
                        .anyMatch(cs -> cs.getStepExecutionId().equals(e.getAggregateId())))
                .toList();
        assertThat(compensationOutbox).hasSize(1);
        assertThat(compensationOutbox).allMatch(e -> e.getEventType().equals("step.execute"));
        // The dispatched step should be the first in reverse order (step2)
        StepExecution firstDispatched = compensationSteps.stream()
                .filter(cs -> cs.getStepExecutionId().equals(compensationOutbox.get(0).getAggregateId()))
                .findFirst().orElseThrow();
        assertThat(firstDispatched.getCompensationFor()).isEqualTo("step2");
    }

    @Test
    void startCompensation_withNoCompensatableSteps_directlyCompensated() {
        // Definition with steps but NO compensations
        Map<String, Object> stepsJson = new LinkedHashMap<>();
        stepsJson.put("step1", Map.of("type", "HTTP_CALL", "maxAttempts", 3));

        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId, "no-comp-test-" + UUID.randomUUID(), 1,
                stepsJson, Map.of(), "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);

        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of(), null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution.fail("Something failed");
        execution = executionRepository.save(execution);

        createSucceededStep(execution, "step1", 0, Map.of("output1", "val1"));

        compensationEngine.startCompensation(execution);

        // Verify execution went directly to COMPENSATED
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATED);

        // Verify no compensation steps were created
        List<StepExecution> allSteps = stepExecutionRepository
                .findByExecutionIdOrderByStepIndex(execution.getExecutionId());
        List<StepExecution> compensationSteps = allSteps.stream()
                .filter(StepExecution::isCompensation)
                .toList();
        assertThat(compensationSteps).isEmpty();
    }

    private StepExecution createSucceededStep(WorkflowExecution execution, String stepName,
                                               int stepIndex, Map<String, Object> output) {
        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, stepName,
                stepIndex, "HTTP_CALL", 3, null, Map.of()
        );
        step.transitionTo(StepStatus.LEASED);
        step.transitionTo(StepStatus.RUNNING);
        step.transitionTo(StepStatus.SUCCEEDED);
        step.setOutputJson(output);
        return stepExecutionRepository.save(step);
    }
}
