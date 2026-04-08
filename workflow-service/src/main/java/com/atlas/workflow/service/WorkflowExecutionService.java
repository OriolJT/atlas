package com.atlas.workflow.service;

import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkflowExecutionService {

    private static final String STEP_EXECUTE_TOPIC = "workflow.step.execute";

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;

    public WorkflowExecutionService(WorkflowExecutionRepository executionRepository,
                                     WorkflowDefinitionRepository definitionRepository,
                                     StepExecutionRepository stepExecutionRepository,
                                     OutboxRepository outboxRepository) {
        this.executionRepository = executionRepository;
        this.definitionRepository = definitionRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public WorkflowExecution startExecution(UUID tenantId, StartExecutionRequest request) {
        // Idempotency check: return existing execution if found
        Optional<WorkflowExecution> existing = executionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // Validate definition exists and is PUBLISHED
        WorkflowDefinition definition = definitionRepository.findById(request.definitionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + request.definitionId()));

        if (definition.getStatus() != DefinitionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow definition must be PUBLISHED to start execution; current status: "
                            + definition.getStatus());
        }

        // Create execution in PENDING, then transition to RUNNING
        Map<String, Object> input = request.input() != null ? request.input() : Map.of();
        WorkflowExecution execution = WorkflowExecution.create(
                tenantId, definition.getDefinitionId(), request.idempotencyKey(), input, null);
        execution.transitionTo(ExecutionStatus.RUNNING);
        execution = executionRepository.save(execution);

        // Parse steps from definition and create first StepExecution
        List<Map.Entry<String, Object>> steps = parseSteps(definition.getStepsJson());
        if (!steps.isEmpty()) {
            Map.Entry<String, Object> firstStep = steps.get(0);
            String stepName = firstStep.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> stepConfig = (Map<String, Object>) firstStep.getValue();
            String stepType = (String) stepConfig.getOrDefault("type", "UNKNOWN");
            int maxAttempts = stepConfig.containsKey("maxAttempts")
                    ? ((Number) stepConfig.get("maxAttempts")).intValue() : 3;
            Long timeoutMs = stepConfig.containsKey("timeoutMs")
                    ? ((Number) stepConfig.get("timeoutMs")).longValue() : null;

            StepExecution stepExecution = StepExecution.create(
                    execution.getExecutionId(), tenantId, stepName, 0, stepType,
                    maxAttempts, timeoutMs, input);
            stepExecution = stepExecutionRepository.save(stepExecution);

            // Write outbox event for step.execute command
            Map<String, Object> payload = Map.of(
                    "stepExecutionId", stepExecution.getStepExecutionId().toString(),
                    "executionId", execution.getExecutionId().toString(),
                    "tenantId", tenantId.toString(),
                    "stepName", stepName,
                    "stepType", stepType,
                    "stepIndex", 0,
                    "input", input
            );

            OutboxEvent outboxEvent = OutboxEvent.create(
                    "StepExecution",
                    stepExecution.getStepExecutionId(),
                    "step.execute",
                    STEP_EXECUTE_TOPIC,
                    payload,
                    tenantId
            );
            outboxRepository.save(outboxEvent);
        }

        return execution;
    }

    @Transactional(readOnly = true)
    public WorkflowExecution getById(UUID executionId, UUID tenantId) {
        return executionRepository.findById(executionId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow execution not found: " + executionId));
    }

    private List<Map.Entry<String, Object>> parseSteps(Map<String, Object> stepsJson) {
        if (stepsJson == null || stepsJson.isEmpty()) {
            return List.of();
        }
        // Sort by key to ensure deterministic step ordering
        List<Map.Entry<String, Object>> entries = new ArrayList<>(stepsJson.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        return entries;
    }
}
