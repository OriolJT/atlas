package com.atlas.workflow.service;

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
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class CompensationEngine {

    private static final Logger log = LoggerFactory.getLogger(CompensationEngine.class);
    private static final String STEP_EXECUTE_TOPIC = "workflow.step.execute";

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;
    private final StepStateMachine stepStateMachine;

    public CompensationEngine(WorkflowExecutionRepository executionRepository,
                              WorkflowDefinitionRepository definitionRepository,
                              StepExecutionRepository stepExecutionRepository,
                              OutboxRepository outboxRepository,
                              StepStateMachine stepStateMachine) {
        this.executionRepository = executionRepository;
        this.definitionRepository = definitionRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
        this.stepStateMachine = stepStateMachine;
    }

    @Transactional
    public void startCompensation(WorkflowExecution execution) {
        log.info("Starting compensation for execution {}", execution.getExecutionId());

        execution.transitionTo(ExecutionStatus.COMPENSATING);
        executionRepository.save(execution);

        WorkflowDefinition definition = definitionRepository.findById(execution.getDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Definition not found for execution: " + execution.getExecutionId()));

        Map<String, Object> compensationsJson = definition.getCompensationsJson();

        // Find SUCCEEDED steps in reverse order (by stepIndex descending)
        List<StepExecution> succeededSteps = stepExecutionRepository
                .findByExecutionIdOrderByStepIndex(execution.getExecutionId())
                .stream()
                .filter(s -> s.getStatus() == StepStatus.SUCCEEDED && !s.isCompensation())
                .sorted(Comparator.comparingInt(StepExecution::getStepIndex).reversed())
                .toList();

        // Filter to only those with compensation definitions
        List<StepExecution> compensatableSteps = new ArrayList<>();
        for (StepExecution step : succeededSteps) {
            if (compensationsJson != null && compensationsJson.containsKey(step.getStepName())) {
                compensatableSteps.add(step);
            }
        }

        if (compensatableSteps.isEmpty()) {
            log.info("No compensatable steps for execution {}, marking as COMPENSATED",
                    execution.getExecutionId());
            execution.transitionTo(ExecutionStatus.COMPENSATED);
            executionRepository.save(execution);
            return;
        }

        // Create compensation StepExecutions and outbox events
        int compensationIndex = 0;
        for (StepExecution originalStep : compensatableSteps) {
            @SuppressWarnings("unchecked")
            Map<String, Object> compensationConfig =
                    (Map<String, Object>) compensationsJson.get(originalStep.getStepName());

            String compensationType = (String) compensationConfig.getOrDefault("type", "UNKNOWN");
            String compensationStepName = "compensate-" + originalStep.getStepName();

            // Use original step's output as compensation input
            Map<String, Object> compensationInput = originalStep.getOutputJson() != null
                    ? originalStep.getOutputJson() : Map.of();

            StepExecution compensationStep = StepExecution.createCompensation(
                    execution.getExecutionId(),
                    execution.getTenantId(),
                    compensationStepName,
                    compensationIndex,
                    compensationType,
                    compensationInput,
                    originalStep.getStepName()
            );
            compensationStep = stepExecutionRepository.save(compensationStep);

            // Mark original step as COMPENSATING
            stepStateMachine.validate(originalStep.getStatus(), StepStatus.COMPENSATING);
            originalStep.transitionTo(StepStatus.COMPENSATING);
            stepExecutionRepository.save(originalStep);

            // Publish execute command via outbox
            Map<String, Object> payload = Map.of(
                    "stepExecutionId", compensationStep.getStepExecutionId().toString(),
                    "executionId", execution.getExecutionId().toString(),
                    "tenantId", execution.getTenantId().toString(),
                    "stepName", compensationStepName,
                    "stepType", compensationType,
                    "stepIndex", compensationIndex,
                    "input", compensationInput,
                    "isCompensation", true
            );

            OutboxEvent outboxEvent = OutboxEvent.create(
                    "StepExecution",
                    compensationStep.getStepExecutionId(),
                    "step.execute",
                    STEP_EXECUTE_TOPIC,
                    payload,
                    execution.getTenantId()
            );
            outboxRepository.save(outboxEvent);

            log.info("Created compensation step {} for original step {} in execution {}",
                    compensationStep.getStepExecutionId(), originalStep.getStepName(),
                    execution.getExecutionId());

            compensationIndex++;
        }
    }

    @Transactional
    public void handleCompensationStepResult(StepExecution compensationStep,
                                              WorkflowExecution execution,
                                              boolean succeeded,
                                              String error) {
        if (succeeded) {
            stepStateMachine.validate(compensationStep.getStatus(), StepStatus.SUCCEEDED);
            compensationStep.transitionTo(StepStatus.SUCCEEDED);
            stepExecutionRepository.save(compensationStep);

            // Mark the original step as COMPENSATED
            String originalStepName = compensationStep.getCompensationFor();
            if (originalStepName != null) {
                stepExecutionRepository.findByExecutionIdOrderByStepIndex(execution.getExecutionId())
                        .stream()
                        .filter(s -> s.getStepName().equals(originalStepName) && !s.isCompensation())
                        .findFirst()
                        .ifPresent(originalStep -> {
                            stepStateMachine.validate(originalStep.getStatus(), StepStatus.COMPENSATED);
                            originalStep.transitionTo(StepStatus.COMPENSATED);
                            stepExecutionRepository.save(originalStep);
                        });
            }

            // Check if all compensation steps are done
            if (allCompensationStepsDone(execution)) {
                log.info("All compensation steps completed for execution {}", execution.getExecutionId());
                execution.transitionTo(ExecutionStatus.COMPENSATED);
                executionRepository.save(execution);
            }
        } else {
            // Compensation step failed -> COMPENSATION_FAILED -> DEAD_LETTERED
            stepStateMachine.validate(compensationStep.getStatus(), StepStatus.COMPENSATION_FAILED);
            compensationStep.setErrorMessage(error);
            compensationStep.transitionTo(StepStatus.COMPENSATION_FAILED);

            stepStateMachine.validate(compensationStep.getStatus(), StepStatus.DEAD_LETTERED);
            compensationStep.transitionTo(StepStatus.DEAD_LETTERED);
            stepExecutionRepository.save(compensationStep);

            log.error("Compensation step {} failed for execution {}: {}",
                    compensationStep.getStepExecutionId(), execution.getExecutionId(), error);

            execution.transitionTo(ExecutionStatus.COMPENSATION_FAILED);
            executionRepository.save(execution);
        }
    }

    private boolean allCompensationStepsDone(WorkflowExecution execution) {
        List<StepExecution> compensationSteps = stepExecutionRepository
                .findByExecutionIdOrderByStepIndex(execution.getExecutionId())
                .stream()
                .filter(StepExecution::isCompensation)
                .toList();

        return compensationSteps.stream()
                .allMatch(s -> s.getStatus() == StepStatus.SUCCEEDED);
    }
}
