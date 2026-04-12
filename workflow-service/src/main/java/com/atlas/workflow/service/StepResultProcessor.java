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
import com.atlas.workflow.scheduler.DelayScheduler;
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.atlas.common.event.EventTypes;

import java.time.Instant;
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
    private final CompensationEngine compensationEngine;
    private final DeadLetterItemRepository deadLetterItemRepository;
    private final DelayScheduler delayScheduler;

    public StepResultProcessor(StepExecutionRepository stepExecutionRepository,
                               WorkflowExecutionRepository executionRepository,
                               WorkflowDefinitionRepository definitionRepository,
                               OutboxRepository outboxRepository,
                               CompensationEngine compensationEngine,
                               DeadLetterItemRepository deadLetterItemRepository,
                               DelayScheduler delayScheduler) {
        this.stepExecutionRepository = stepExecutionRepository;
        this.executionRepository = executionRepository;
        this.definitionRepository = definitionRepository;
        this.outboxRepository = outboxRepository;
        this.compensationEngine = compensationEngine;
        this.deadLetterItemRepository = deadLetterItemRepository;
        this.delayScheduler = delayScheduler;
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

        // Deduplication: only process if step hasn't already been completed
        if (step.getStatus() == StepStatus.SUCCEEDED || step.getStatus() == StepStatus.DEAD_LETTERED
                || step.getStatus() == StepStatus.COMPENSATED) {
            log.info("Duplicate result ignored for step {} (already in terminal status {})",
                    stepExecutionId, step.getStatus());
            return;
        }

        // Transition step to RUNNING if still PENDING/LEASED (worker executed it)
        if (step.getStatus() == StepStatus.PENDING) {
            step.transitionTo(StepStatus.LEASED);
            step.transitionTo(StepStatus.RUNNING);
            stepExecutionRepository.save(step);
        } else if (step.getStatus() == StepStatus.LEASED) {
            step.transitionTo(StepStatus.RUNNING);
            stepExecutionRepository.save(step);
        }

        // Only process results for steps in RUNNING or WAITING status
        if (step.getStatus() != StepStatus.RUNNING && step.getStatus() != StepStatus.WAITING) {
            log.info("Ignoring result for step {} in status {}", stepExecutionId, step.getStatus());
            return;
        }

        Boolean nonRetryable = resultPayload.containsKey("non_retryable")
                ? Boolean.TRUE.equals(resultPayload.get("non_retryable"))
                : false;

        switch (outcome) {
            case "SUCCEEDED" -> handleSucceeded(step, execution, output);
            case "FAILED" -> handleFailed(step, execution, error, nonRetryable);
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

        StepStateMachine.validate(step.getStatus(), StepStatus.SUCCEEDED);
        step.transitionTo(StepStatus.SUCCEEDED);
        step.setOutputJson(output);
        stepExecutionRepository.save(step);

        // Check if this was the last step
        WorkflowDefinition definition = definitionRepository.findById(execution.getDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition not found: " + execution.getDefinitionId()));

        List<Map<String, Object>> steps = StepDefinitionParser.parseSteps(definition.getStepsJson());
        int nextStepIndex = step.getStepIndex() + 1;

        if (nextStepIndex >= steps.size()) {
            // Last step completed -> execution COMPLETED
            execution.setOutputJson(output);
            execution.transitionTo(ExecutionStatus.COMPLETED);
            executionRepository.save(execution);
            log.info("Execution {} completed successfully", execution.getExecutionId());
        } else {
            // Create next step
            Map<String, Object> nextStepDef = steps.get(nextStepIndex);
            String nextStepName = (String) nextStepDef.getOrDefault("name", "step-" + nextStepIndex);
            String stepType = (String) nextStepDef.getOrDefault("type", "UNKNOWN");
            int maxAttempts = StepDefinitionParser.extractMaxAttempts(nextStepDef);
            Long timeoutMs = nextStepDef.containsKey("timeout_ms")
                    ? ((Number) nextStepDef.get("timeout_ms")).longValue() : null;

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

    private void handleFailed(StepExecution step, WorkflowExecution execution, String error,
                              boolean nonRetryable) {
        if (step.isCompensation()) {
            // Delegate to CompensationEngine
            compensationEngine.handleCompensationStepResult(step, execution, false, error);
            return;
        }

        // Transition RUNNING -> FAILED first
        StepStateMachine.validate(step.getStatus(), StepStatus.FAILED);
        step.transitionTo(StepStatus.FAILED);
        step.setErrorMessage(error);
        stepExecutionRepository.save(step);

        if (!nonRetryable && step.getAttemptCount() < step.getMaxAttempts()) {
            // Retry with exponential backoff
            long backoffMs = calculateBackoff(step.getAttemptCount());
            Instant nextRetryAt = Instant.now().plusMillis(backoffMs);

            StepStateMachine.validate(step.getStatus(), StepStatus.RETRY_SCHEDULED);
            step.scheduleRetry(nextRetryAt);
            stepExecutionRepository.save(step);

            log.info("Step {} scheduled for retry at {} (attempt {}/{})",
                    step.getStepExecutionId(), nextRetryAt,
                    step.getAttemptCount(), step.getMaxAttempts());
        } else {
            // No retries left -> dead-letter and start compensation
            StepStateMachine.validate(step.getStatus(), StepStatus.DEAD_LETTERED);
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
        Instant wakeUp = Instant.now().plusMillis(delay);

        // RUNNING -> RETRY_SCHEDULED directly (delay is not a failure)
        StepStateMachine.validate(step.getStatus(), StepStatus.RETRY_SCHEDULED);
        step.scheduleRetry(wakeUp);
        stepExecutionRepository.save(step);

        // Schedule via Redis sorted set for precise wake-up
        delayScheduler.schedule(step.getStepExecutionId(), wakeUp.toEpochMilli());

        log.info("Step {} delay requested, scheduled in Redis delay queue for {}",
                step.getStepExecutionId(), wakeUp);
    }

    private void handleWaiting(StepExecution step, WorkflowExecution execution) {
        StepStateMachine.validate(step.getStatus(), StepStatus.WAITING);
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

    // parseSteps() and extractMaxAttempts() moved to StepDefinitionParser
}
