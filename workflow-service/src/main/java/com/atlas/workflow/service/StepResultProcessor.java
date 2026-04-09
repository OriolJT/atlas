package com.atlas.workflow.service;

import com.atlas.workflow.domain.DeadLetterItem;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.DeadLetterItemRepository;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atlas.common.event.EventTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StepResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(StepResultProcessor.class);
    private static final String STEP_EXECUTE_TOPIC = EventTypes.TOPIC_STEP_EXECUTE;
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private final StepExecutionRepository stepExecutionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final OutboxRepository outboxRepository;
    private final StepStateMachine stepStateMachine;
    private final CompensationEngine compensationEngine;
    private final DeadLetterItemRepository deadLetterItemRepository;

    public StepResultProcessor(StepExecutionRepository stepExecutionRepository,
                               WorkflowExecutionRepository executionRepository,
                               WorkflowDefinitionRepository definitionRepository,
                               OutboxRepository outboxRepository,
                               StepStateMachine stepStateMachine,
                               CompensationEngine compensationEngine,
                               DeadLetterItemRepository deadLetterItemRepository) {
        this.stepExecutionRepository = stepExecutionRepository;
        this.executionRepository = executionRepository;
        this.definitionRepository = definitionRepository;
        this.outboxRepository = outboxRepository;
        this.stepStateMachine = stepStateMachine;
        this.compensationEngine = compensationEngine;
        this.deadLetterItemRepository = deadLetterItemRepository;
    }

    @Transactional
    public void process(Map<String, Object> resultPayload) {
        UUID stepExecutionId = UUID.fromString((String) resultPayload.get("step_execution_id"));
        String outcome = (String) resultPayload.get("outcome");
        int attemptCount = ((Number) resultPayload.get("attempt")).intValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) resultPayload.get("output");
        String error = (String) resultPayload.get("error");
        Long delayMs = resultPayload.containsKey("delay_ms")
                ? ((Number) resultPayload.get("delay_ms")).longValue() : null;

        StepExecution step = stepExecutionRepository.findById(stepExecutionId).orElse(null);
        if (step == null) {
            log.warn("Step execution not found: {}", stepExecutionId);
            return;
        }

        // Derive executionId from the step entity (worker results may not include it)
        UUID executionId = step.getExecutionId();
        WorkflowExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            log.warn("Workflow execution not found: {}", executionId);
            return;
        }

        // Ignore results for CANCELED executions
        if (execution.getStatus() == ExecutionStatus.CANCELED) {
            log.info("Ignoring result for canceled execution {}", executionId);
            return;
        }

        // Deduplication: check step_execution_id + attempt_count
        if (step.getAttemptCount() != attemptCount) {
            log.info("Duplicate result ignored for step {} (expected attempt {}, got {})",
                    stepExecutionId, step.getAttemptCount(), attemptCount);
            return;
        }

        // Only process results for steps in RUNNING or WAITING status
        if (step.getStatus() != StepStatus.RUNNING && step.getStatus() != StepStatus.WAITING) {
            log.info("Ignoring result for step {} in status {}", stepExecutionId, step.getStatus());
            return;
        }

        switch (outcome) {
            case "SUCCEEDED" -> handleSucceeded(step, execution, output);
            case "FAILED" -> handleFailed(step, execution, error);
            case "DELAY_REQUESTED" -> handleDelayRequested(step, delayMs);
            case "WAITING" -> handleWaiting(step, execution);
            default -> log.warn("Unknown outcome '{}' for step {}", outcome, stepExecutionId);
        }
    }

    private void handleSucceeded(StepExecution step, WorkflowExecution execution,
                                  Map<String, Object> output) {
        if (step.isCompensation()) {
            // Delegate to CompensationEngine
            compensationEngine.handleCompensationStepResult(step, execution, true, null);
            return;
        }

        stepStateMachine.validate(step.getStatus(), StepStatus.SUCCEEDED);
        step.transitionTo(StepStatus.SUCCEEDED);
        step.setOutputJson(output);
        stepExecutionRepository.save(step);

        // Check if this was the last step
        WorkflowDefinition definition = definitionRepository.findById(execution.getDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition not found: " + execution.getDefinitionId()));

        List<Map.Entry<String, Object>> steps = parseSteps(definition.getStepsJson());
        int nextStepIndex = step.getStepIndex() + 1;

        if (nextStepIndex >= steps.size()) {
            // Last step completed -> execution COMPLETED
            execution.setOutputJson(output);
            execution.transitionTo(ExecutionStatus.COMPLETED);
            executionRepository.save(execution);
            log.info("Execution {} completed successfully", execution.getExecutionId());
        } else {
            // Create next step
            Map.Entry<String, Object> nextStepEntry = steps.get(nextStepIndex);
            String nextStepName = nextStepEntry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> nextStepConfig = (Map<String, Object>) nextStepEntry.getValue();
            String stepType = (String) nextStepConfig.getOrDefault("type", "UNKNOWN");
            int maxAttempts = nextStepConfig.containsKey("maxAttempts")
                    ? ((Number) nextStepConfig.get("maxAttempts")).intValue() : 3;
            Long timeoutMs = nextStepConfig.containsKey("timeoutMs")
                    ? ((Number) nextStepConfig.get("timeoutMs")).longValue() : null;

            // Output of current step becomes input of next step
            Map<String, Object> nextInput = output != null ? output : Map.of();

            StepExecution nextStep = StepExecution.create(
                    execution.getExecutionId(),
                    execution.getTenantId(),
                    nextStepName,
                    nextStepIndex,
                    stepType,
                    maxAttempts,
                    timeoutMs,
                    nextInput
            );
            nextStep = stepExecutionRepository.save(nextStep);

            // Publish outbox event for next step
            Map<String, Object> payload = Map.of(
                    "step_execution_id", nextStep.getStepExecutionId().toString(),
                    "execution_id", execution.getExecutionId().toString(),
                    "tenant_id", execution.getTenantId().toString(),
                    "step_name", nextStepName,
                    "step_type", stepType,
                    "step_index", nextStepIndex,
                    "input", nextInput
            );

            OutboxEvent outboxEvent = OutboxEvent.create(
                    "StepExecution",
                    nextStep.getStepExecutionId(),
                    "step.execute",
                    STEP_EXECUTE_TOPIC,
                    payload,
                    execution.getTenantId()
            );
            outboxRepository.save(outboxEvent);

            log.info("Created next step {} (index {}) for execution {}",
                    nextStep.getStepExecutionId(), nextStepIndex, execution.getExecutionId());
        }
    }

    private void handleFailed(StepExecution step, WorkflowExecution execution, String error) {
        if (step.isCompensation()) {
            // Delegate to CompensationEngine
            compensationEngine.handleCompensationStepResult(step, execution, false, error);
            return;
        }

        // Transition RUNNING -> FAILED first
        stepStateMachine.validate(step.getStatus(), StepStatus.FAILED);
        step.transitionTo(StepStatus.FAILED);
        step.setErrorMessage(error);
        stepExecutionRepository.save(step);

        if (step.getAttemptCount() < step.getMaxAttempts()) {
            // Retry with exponential backoff
            long backoffMs = calculateBackoff(step.getAttemptCount());
            Instant nextRetryAt = Instant.now().plusMillis(backoffMs);

            stepStateMachine.validate(step.getStatus(), StepStatus.RETRY_SCHEDULED);
            step.scheduleRetry(nextRetryAt);
            stepExecutionRepository.save(step);

            log.info("Step {} scheduled for retry at {} (attempt {}/{})",
                    step.getStepExecutionId(), nextRetryAt,
                    step.getAttemptCount(), step.getMaxAttempts());
        } else {
            // No retries left -> dead-letter and start compensation
            stepStateMachine.validate(step.getStatus(), StepStatus.DEAD_LETTERED);
            step.transitionTo(StepStatus.DEAD_LETTERED);
            stepExecutionRepository.save(step);

            // Create dead-letter item for observability and manual replay
            DeadLetterItem deadLetterItem = DeadLetterItem.create(
                    step.getTenantId(),
                    step.getExecutionId(),
                    step.getStepExecutionId(),
                    step.getStepName(),
                    error,
                    step.getAttemptCount(),
                    step.getInputJson()
            );
            deadLetterItemRepository.save(deadLetterItem);

            log.warn("Step {} exhausted all retries, dead-lettering. Starting compensation for execution {}",
                    step.getStepExecutionId(), execution.getExecutionId());

            // Fail the execution first, then start compensation
            execution.fail(error);
            executionRepository.save(execution);

            compensationEngine.startCompensation(execution);
        }
    }

    private void handleDelayRequested(StepExecution step, Long delayMs) {
        long delay = delayMs != null ? delayMs : BASE_BACKOFF_MS;
        Instant nextRetryAt = Instant.now().plusMillis(delay);

        // RUNNING -> FAILED -> RETRY_SCHEDULED
        stepStateMachine.validate(step.getStatus(), StepStatus.FAILED);
        step.transitionTo(StepStatus.FAILED);

        stepStateMachine.validate(step.getStatus(), StepStatus.RETRY_SCHEDULED);
        step.scheduleRetry(nextRetryAt);
        stepExecutionRepository.save(step);

        log.info("Step {} delay requested, scheduled retry at {}", step.getStepExecutionId(), nextRetryAt);
    }

    private void handleWaiting(StepExecution step, WorkflowExecution execution) {
        stepStateMachine.validate(step.getStatus(), StepStatus.WAITING);
        step.transitionTo(StepStatus.WAITING);
        stepExecutionRepository.save(step);

        execution.transitionTo(ExecutionStatus.WAITING);
        executionRepository.save(execution);

        log.info("Step {} and execution {} transitioned to WAITING",
                step.getStepExecutionId(), execution.getExecutionId());
    }

    long calculateBackoff(int attemptCount) {
        long backoff = BASE_BACKOFF_MS * (1L << (attemptCount - 1));
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private List<Map.Entry<String, Object>> parseSteps(Map<String, Object> stepsJson) {
        if (stepsJson == null || stepsJson.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(stepsJson.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        return entries;
    }
}
