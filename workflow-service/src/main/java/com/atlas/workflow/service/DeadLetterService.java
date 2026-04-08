package com.atlas.workflow.service;

import com.atlas.workflow.domain.DeadLetterItem;
import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.DeadLetterItemRepository;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final String STEP_EXECUTE_TOPIC = "workflow.step.execute";

    private final DeadLetterItemRepository deadLetterItemRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;

    public DeadLetterService(DeadLetterItemRepository deadLetterItemRepository,
                             StepExecutionRepository stepExecutionRepository,
                             OutboxRepository outboxRepository) {
        this.deadLetterItemRepository = deadLetterItemRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public List<DeadLetterItem> list(UUID tenantId) {
        return deadLetterItemRepository.findByTenantIdAndReplayedFalse(tenantId);
    }

    @Transactional
    public DeadLetterItem replay(UUID deadLetterId, UUID tenantId) {
        DeadLetterItem item = deadLetterItemRepository
                .findByDeadLetterIdAndTenantId(deadLetterId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dead-letter item not found: " + deadLetterId));

        if (item.isReplayed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dead-letter item has already been replayed: " + deadLetterId);
        }

        // Mark the dead-letter item as replayed
        item.replay();
        deadLetterItemRepository.save(item);

        // Reset the step back to PENDING
        StepExecution step = stepExecutionRepository.findById(item.getStepExecutionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Step execution not found: " + item.getStepExecutionId()));

        step.transitionTo(StepStatus.PENDING);
        stepExecutionRepository.save(step);

        // Publish outbox event to re-execute the step
        Map<String, Object> payload = Map.of(
                "stepExecutionId", step.getStepExecutionId().toString(),
                "executionId", step.getExecutionId().toString(),
                "tenantId", step.getTenantId().toString(),
                "stepName", step.getStepName(),
                "stepType", step.getStepType(),
                "stepIndex", step.getStepIndex(),
                "input", step.getInputJson()
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

        log.info("Replayed dead-letter item {} for step {} (execution {})",
                deadLetterId, step.getStepExecutionId(), step.getExecutionId());

        return item;
    }
}
