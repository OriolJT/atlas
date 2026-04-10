package com.atlas.workflow.chaos;

import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.service.StepResultProcessor;
import com.atlas.workflow.service.WorkflowExecutionService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class ChaosTestHelper {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final WorkflowExecutionService workflowExecutionService;
    private final StepResultProcessor stepResultProcessor;

    ChaosTestHelper(WorkflowDefinitionRepository definitionRepository,
                    WorkflowExecutionRepository executionRepository,
                    StepExecutionRepository stepExecutionRepository,
                    WorkflowExecutionService workflowExecutionService,
                    StepResultProcessor stepResultProcessor) {
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.workflowExecutionService = workflowExecutionService;
        this.stepResultProcessor = stepResultProcessor;
    }

    /**
     * Creates a PUBLISHED workflow definition with the given steps and compensations.
     *
     * @param tenantId       tenant scope
     * @param name           definition name (should be unique per tenant+version)
     * @param steps          list of step definition maps (each with "name", "type", optional "retry_policy")
     * @param compensations  map of stepName -> compensation config (may be null or empty)
     * @return the persisted, published definition
     */
    WorkflowDefinition createPublishedDefinition(UUID tenantId, String name,
                                                  List<Map<String, Object>> steps,
                                                  Map<String, Object> compensations) {
        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId, name, 1, steps,
                compensations != null ? compensations : Map.of(),
                "MANUAL"
        );
        definition.publish();
        return definitionRepository.save(definition);
    }

    /**
     * Starts an execution via the service layer (full idempotency + quota flow).
     */
    WorkflowExecution startExecution(UUID tenantId, UUID definitionId, Map<String, Object> input) {
        StartExecutionRequest request = new StartExecutionRequest(
                definitionId,
                "idem-" + UUID.randomUUID(),
                input
        );
        return workflowExecutionService.startExecution(tenantId, request);
    }

    /**
     * Starts an execution with an explicit idempotency key.
     */
    WorkflowExecution startExecution(UUID tenantId, UUID definitionId,
                                     String idempotencyKey, Map<String, Object> input) {
        StartExecutionRequest request = new StartExecutionRequest(
                definitionId,
                idempotencyKey,
                input
        );
        return workflowExecutionService.startExecution(tenantId, request);
    }

    /**
     * Simulates a worker reporting success for the given step.
     */
    void simulateWorkerSuccess(StepExecution step, Map<String, Object> output) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step_execution_id", step.getStepExecutionId().toString());
        payload.put("outcome", "SUCCEEDED");
        payload.put("attempt", step.getAttemptCount());
        payload.put("output", output != null ? output : Map.of());
        payload.put("error", null);
        stepResultProcessor.process(payload);
    }

    /**
     * Simulates a worker reporting failure for the given step.
     *
     * @param nonRetryable if true, marks the failure as permanent/non-retryable
     */
    void simulateWorkerFailure(StepExecution step, String error, boolean nonRetryable) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step_execution_id", step.getStepExecutionId().toString());
        payload.put("outcome", "FAILED");
        payload.put("attempt", step.getAttemptCount());
        payload.put("output", null);
        payload.put("error", error);
        payload.put("non_retryable", nonRetryable);
        stepResultProcessor.process(payload);
    }

    /**
     * Returns the first PENDING step for the given execution.
     */
    StepExecution getNextPendingStep(UUID executionId) {
        return stepExecutionRepository.findByExecutionIdOrderByStepIndex(executionId)
                .stream()
                .filter(s -> s.getStatus() == StepStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns all steps for the given execution, ordered by stepIndex.
     */
    List<StepExecution> getAllSteps(UUID executionId) {
        return stepExecutionRepository.findByExecutionIdOrderByStepIndex(executionId);
    }

    /**
     * Reloads the execution from the database.
     */
    WorkflowExecution refreshExecution(UUID executionId) {
        return executionRepository.findById(executionId).orElseThrow();
    }

    /**
     * Reloads a step from the database.
     */
    StepExecution refreshStep(UUID stepExecutionId) {
        return stepExecutionRepository.findById(stepExecutionId).orElseThrow();
    }

    /**
     * Simulates the RetryScheduler: transitions a RETRY_SCHEDULED step back to PENDING
     * so the next process() call can pick it up again.
     */
    StepExecution transitionRetryToPending(StepExecution step) {
        step = refreshStep(step.getStepExecutionId());
        step.transitionTo(StepStatus.PENDING);
        return stepExecutionRepository.save(step);
    }
}
