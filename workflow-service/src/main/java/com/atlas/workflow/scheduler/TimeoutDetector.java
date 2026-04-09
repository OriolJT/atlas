package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
import com.atlas.workflow.service.StepResultProcessor;
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class TimeoutDetector {

    private static final Logger log = LoggerFactory.getLogger(TimeoutDetector.class);

    private final StepExecutionRepository stepExecutionRepository;
    private final StepStateMachine stepStateMachine;
    private final StepResultProcessor stepResultProcessor;

    public TimeoutDetector(StepExecutionRepository stepExecutionRepository,
                           StepStateMachine stepStateMachine,
                           StepResultProcessor stepResultProcessor) {
        this.stepExecutionRepository = stepExecutionRepository;
        this.stepStateMachine = stepStateMachine;
        this.stepResultProcessor = stepResultProcessor;
    }

    @Scheduled(fixedDelay = 10000)
    public void detectTimedOutSteps() {
        List<StepExecution> candidates = stepExecutionRepository.findRunningOrLeased();
        if (candidates.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int timedOutCount = 0;

        for (StepExecution step : candidates) {
            if (isTimedOut(step, now)) {
                handleTimedOutStep(step);
                timedOutCount++;
            }
        }

        if (timedOutCount > 0) {
            log.info("TimeoutDetector: marked {} step(s) as timed out", timedOutCount);
        }
    }

    private boolean isTimedOut(StepExecution step, Instant now) {
        Long timeoutMs = step.getTimeoutMs();
        if (timeoutMs == null) {
            return false;
        }

        if (step.getStatus() == StepStatus.RUNNING && step.getStartedAt() != null) {
            return step.getStartedAt().plusMillis(timeoutMs).isBefore(now);
        }
        if (step.getStatus() == StepStatus.LEASED && step.getLeasedAt() != null) {
            return step.getLeasedAt().plusMillis(timeoutMs).isBefore(now);
        }
        return false;
    }

    @Transactional
    protected void handleTimedOutStep(StepExecution step) {
        log.warn("Step {} (execution {}) timed out in status {}",
                step.getStepExecutionId(), step.getExecutionId(), step.getStatus());

        // Delegate to StepResultProcessor by synthesising a FAILED result
        // This reuses the retry/compensation/dead-letter logic already in StepResultProcessor.
        // First transition through RUNNING if currently LEASED so the state machine is satisfied.
        if (step.getStatus() == StepStatus.LEASED) {
            stepStateMachine.validate(StepStatus.LEASED, StepStatus.RUNNING);
            step.transitionTo(StepStatus.RUNNING);
            stepExecutionRepository.save(step);
        }

        Map<String, Object> failedResult = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "outcome", "FAILED",
                "attempt", step.getAttemptCount(),
                "error", "Step timed out after " + step.getTimeoutMs() + "ms"
        );

        stepResultProcessor.process(failedResult);
    }
}
