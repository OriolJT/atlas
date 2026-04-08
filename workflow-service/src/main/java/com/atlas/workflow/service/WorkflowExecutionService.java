package com.atlas.workflow.service;

import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.dto.SignalRequest;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.dto.TimelineResponse;
import com.atlas.workflow.dto.TimelineResponse.TimelineEvent;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.statemachine.ExecutionStateMachine;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final StepResultProcessor stepResultProcessor;

    public WorkflowExecutionService(WorkflowExecutionRepository executionRepository,
                                     WorkflowDefinitionRepository definitionRepository,
                                     StepExecutionRepository stepExecutionRepository,
                                     OutboxRepository outboxRepository,
                                     @Lazy StepResultProcessor stepResultProcessor) {
        this.executionRepository = executionRepository;
        this.definitionRepository = definitionRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
        this.stepResultProcessor = stepResultProcessor;
    }

    @Transactional
    public WorkflowExecution startExecution(UUID tenantId, StartExecutionRequest request) {
        // Idempotency check: return existing execution if found
        Optional<WorkflowExecution> existing = executionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // Validate definition exists and is PUBLISHED (tenant-scoped lookup)
        WorkflowDefinition definition = definitionRepository
                .findByDefinitionIdAndTenantId(request.definitionId(), tenantId)
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

    @Transactional
    public WorkflowExecution signal(UUID executionId, UUID tenantId, SignalRequest request) {
        WorkflowExecution execution = getById(executionId, tenantId);

        if (execution.getStatus() != ExecutionStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Execution is not in WAITING state; current status: " + execution.getStatus());
        }

        List<StepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByStepIndex(executionId);
        StepExecution waitingStep = steps.stream()
                .filter(s -> s.getStepName().equals(request.stepName()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Step not found: " + request.stepName()));

        if (waitingStep.getStatus() != StepStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Step '" + request.stepName() + "' is not in WAITING state; current status: "
                            + waitingStep.getStatus());
        }

        // Build a synthetic result payload and delegate to StepResultProcessor
        Map<String, Object> resultPayload = new HashMap<>();
        resultPayload.put("stepExecutionId", waitingStep.getStepExecutionId().toString());
        resultPayload.put("executionId", executionId.toString());
        resultPayload.put("outcome", "SUCCEEDED");
        resultPayload.put("attemptCount", waitingStep.getAttemptCount());
        resultPayload.put("output", request.payload() != null ? request.payload() : Map.of());
        resultPayload.put("error", null);

        stepResultProcessor.process(resultPayload);

        return getById(executionId, tenantId);
    }

    @Transactional
    public WorkflowExecution cancel(UUID executionId, UUID tenantId) {
        WorkflowExecution execution = getById(executionId, tenantId);

        if (!ExecutionStateMachine.canTransition(execution.getStatus(), ExecutionStatus.CANCELED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Execution cannot be canceled from status: " + execution.getStatus());
        }

        execution.transitionTo(ExecutionStatus.CANCELED);
        executionRepository.save(execution);
        return execution;
    }

    @Transactional(readOnly = true)
    public TimelineResponse getTimeline(UUID executionId, UUID tenantId) {
        WorkflowExecution execution = getById(executionId, tenantId);

        List<StepExecution> steps = stepExecutionRepository.findByExecutionIdOrderByStepIndex(executionId);

        List<TimelineEvent> events = new ArrayList<>();
        for (StepExecution step : steps) {
            List<Map<String, Object>> history = step.getStateHistory();
            if (history == null) continue;
            for (Map<String, Object> entry : history) {
                String timestamp = entry.containsKey("at") ? entry.get("at").toString() : "";
                String type = entry.containsKey("status") ? entry.get("status").toString() : "UNKNOWN";
                Map<String, Object> detail = new HashMap<>(entry);

                events.add(new TimelineEvent(
                        timestamp,
                        type,
                        step.getStepName(),
                        step.getAttemptCount(),
                        detail
                ));
            }
        }

        events.sort(Comparator.comparing(TimelineEvent::timestamp));

        return new TimelineResponse(
                execution.getExecutionId(),
                execution.getStatus().name(),
                events
        );
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
