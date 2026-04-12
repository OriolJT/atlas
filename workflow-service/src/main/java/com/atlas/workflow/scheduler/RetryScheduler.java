package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.atlas.common.event.EventTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final String STEP_EXECUTE_TOPIC = EventTypes.TOPIC_STEP_EXECUTE;

    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;

    public RetryScheduler(StepExecutionRepository stepExecutionRepository,
                          OutboxRepository outboxRepository) {
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void retryDueSteps() {
        List<StepExecution> dueSteps = stepExecutionRepository.findDueForRetry(Instant.now(), PageRequest.of(0, 100));
        if (dueSteps.isEmpty()) {
            return;
        }

        log.info("RetryScheduler: found {} step(s) due for retry", dueSteps.size());

        for (StepExecution step : dueSteps) {
            retryStep(step);
        }
    }

    private void retryStep(StepExecution step) {
        StepStateMachine.validate(step.getStatus(), StepStatus.PENDING);
        step.transitionTo(StepStatus.PENDING);
        stepExecutionRepository.save(step);

        Map<String, Object> payload = Map.of(
                "step_execution_id", step.getStepExecutionId().toString(),
                "execution_id", step.getExecutionId().toString(),
                "tenant_id", step.getTenantId().toString(),
                "step_name", step.getStepName(),
                "step_type", step.getStepType(),
                "step_index", step.getStepIndex(),
                "input", step.getInputJson() != null ? step.getInputJson() : Map.of()
        );

        OutboxEvent outboxEvent = OutboxEvent.create(
                "StepExecution",
                step.getStepExecutionId(),
                "step.execute",
                STEP_EXECUTE_TOPIC,
                payload,
                step.getTenantId()
        );
        outboxRepository.save(outboxEvent);

        log.info("Step {} (execution {}) transitioned RETRY_SCHEDULED -> PENDING, outbox event created",
                step.getStepExecutionId(), step.getExecutionId());
    }
}
