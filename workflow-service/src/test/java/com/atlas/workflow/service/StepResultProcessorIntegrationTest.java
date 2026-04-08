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
class StepResultProcessorIntegrationTest {

    @Autowired
    private StepResultProcessor stepResultProcessor;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowDefinitionRepository definitionRepository;

    @Autowired
    private StepExecutionRepository stepExecutionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private UUID tenantId;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        // Create a definition with 2 steps, sorted alphabetically: step1, step2
        Map<String, Object> stepsJson = new LinkedHashMap<>();
        stepsJson.put("step1", Map.of("type", "HTTP_CALL", "maxAttempts", 3));
        stepsJson.put("step2", Map.of("type", "HTTP_CALL", "maxAttempts", 2));

        definition = WorkflowDefinition.create(
                tenantId, "test-workflow-" + UUID.randomUUID(), 1,
                stepsJson, Map.of(), "MANUAL"
        );
        definition.publish();
        definition = definitionRepository.save(definition);
    }

    @Test
    void processSucceeded_lastStep_completesExecution() {
        // Create execution and step at the last index (step2 = index 1)
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStep(execution, "step2", 1, "HTTP_CALL", 3, StepStatus.RUNNING);

        Map<String, Object> result = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", execution.getExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attemptCount", step.getAttemptCount(),
                "output", Map.of("result", "done")
        );

        stepResultProcessor.process(result);

        StepExecution updatedStep = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();

        assertThat(updatedStep.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(updatedStep.getOutputJson()).containsEntry("result", "done");
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(updatedExecution.getOutputJson()).containsEntry("result", "done");
    }

    @Test
    void processSucceeded_midStep_createsNextStepInPending() {
        // Create execution and step at the first index (step1 = index 0)
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStep(execution, "step1", 0, "HTTP_CALL", 3, StepStatus.RUNNING);

        Map<String, Object> result = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", execution.getExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attemptCount", step.getAttemptCount(),
                "output", Map.of("intermediateResult", "value1")
        );

        stepResultProcessor.process(result);

        StepExecution updatedStep = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updatedStep.getStatus()).isEqualTo(StepStatus.SUCCEEDED);

        // Verify next step was created
        List<StepExecution> allSteps = stepExecutionRepository
                .findByExecutionIdOrderByStepIndex(execution.getExecutionId());
        assertThat(allSteps).hasSize(2);

        StepExecution nextStep = allSteps.stream()
                .filter(s -> s.getStepIndex() == 1)
                .findFirst().orElseThrow();
        assertThat(nextStep.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(nextStep.getStepName()).isEqualTo("step2");
        assertThat(nextStep.getInputJson()).containsEntry("intermediateResult", "value1");

        // Verify outbox event was created
        List<OutboxEvent> outboxEvents = outboxRepository.findUnpublished();
        assertThat(outboxEvents).anyMatch(e ->
                e.getAggregateId().equals(nextStep.getStepExecutionId())
                        && e.getEventType().equals("step.execute"));

        // Execution should still be RUNNING
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void processFailed_withRetriesLeft_schedulesRetry() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStep(execution, "step1", 0, "HTTP_CALL", 3, StepStatus.RUNNING);

        Map<String, Object> result = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", execution.getExecutionId().toString(),
                "outcome", "FAILED",
                "attemptCount", step.getAttemptCount(),
                "error", "Connection timeout"
        );

        stepResultProcessor.process(result);

        StepExecution updatedStep = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(updatedStep.getStatus()).isEqualTo(StepStatus.RETRY_SCHEDULED);
        assertThat(updatedStep.getNextRetryAt()).isNotNull();
        assertThat(updatedStep.getErrorMessage()).isEqualTo("Connection timeout");

        // Execution should still be RUNNING
        WorkflowExecution updatedExecution = executionRepository.findById(execution.getExecutionId()).orElseThrow();
        assertThat(updatedExecution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void processDuplicate_isIgnored() {
        WorkflowExecution execution = createRunningExecution();
        StepExecution step = createStep(execution, "step1", 0, "HTTP_CALL", 3, StepStatus.RUNNING);

        // Process first result
        Map<String, Object> result = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", execution.getExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attemptCount", step.getAttemptCount(),
                "output", Map.of("result", "first")
        );
        stepResultProcessor.process(result);

        StepExecution afterFirst = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(StepStatus.SUCCEEDED);

        // Process duplicate with same attempt count — step is now SUCCEEDED, so it should be ignored
        Map<String, Object> duplicate = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", execution.getExecutionId().toString(),
                "outcome", "SUCCEEDED",
                "attemptCount", step.getAttemptCount(),
                "output", Map.of("result", "duplicate")
        );
        stepResultProcessor.process(duplicate);

        // Verify output was NOT overwritten
        StepExecution afterDuplicate = stepExecutionRepository.findById(step.getStepExecutionId()).orElseThrow();
        assertThat(afterDuplicate.getOutputJson()).containsEntry("result", "first");
    }

    private WorkflowExecution createRunningExecution() {
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(),
                "idem-" + UUID.randomUUID(), Map.of("initial", "input"), null
        );
        execution.transitionTo(ExecutionStatus.RUNNING);
        return executionRepository.save(execution);
    }

    private StepExecution createStep(WorkflowExecution execution, String stepName,
                                      int stepIndex, String stepType, int maxAttempts,
                                      StepStatus targetStatus) {
        StepExecution step = StepExecution.create(
                execution.getExecutionId(), tenantId, stepName,
                stepIndex, stepType, maxAttempts, null, execution.getInputJson()
        );
        // Transition through valid states to reach targetStatus
        if (targetStatus == StepStatus.RUNNING) {
            step.transitionTo(StepStatus.LEASED);
            step.transitionTo(StepStatus.RUNNING);
        }
        return stepExecutionRepository.save(step);
    }
}
