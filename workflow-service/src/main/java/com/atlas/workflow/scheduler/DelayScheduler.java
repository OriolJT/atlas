package com.atlas.workflow.scheduler;

import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.repository.OutboxRepository;
import com.atlas.workflow.repository.StepExecutionRepository;
import com.atlas.workflow.statemachine.StepStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.atlas.common.event.EventTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(DelayScheduler.class);
    static final String DELAY_QUEUE_KEY = "atlas:delay-queue";
    private static final String STEP_EXECUTE_TOPIC = EventTypes.TOPIC_STEP_EXECUTE;

    /**
     * Lua script: atomically fetch and remove entries with score <= cutoff.
     * Returns the members that were removed (claimed).
     */
    private static final String CLAIM_LUA_SCRIPT =
            "local items = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 100) " +
            "if #items > 0 then " +
            "  for i, v in ipairs(items) do " +
            "    redis.call('ZREM', KEYS[1], v) " +
            "  end " +
            "end " +
            "return items";

    private final DefaultRedisScript<List> claimScript;

    private final StringRedisTemplate redisTemplate;
    private final StepExecutionRepository stepExecutionRepository;
    private final OutboxRepository outboxRepository;
    private final StepStateMachine stepStateMachine;

    public DelayScheduler(StringRedisTemplate redisTemplate,
                          StepExecutionRepository stepExecutionRepository,
                          OutboxRepository outboxRepository,
                          StepStateMachine stepStateMachine) {
        this.redisTemplate = redisTemplate;
        this.stepExecutionRepository = stepExecutionRepository;
        this.outboxRepository = outboxRepository;
        this.stepStateMachine = stepStateMachine;

        this.claimScript = new DefaultRedisScript<>();
        this.claimScript.setScriptText(CLAIM_LUA_SCRIPT);
        this.claimScript.setResultType(List.class);
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollDelayQueue() {
        long nowMs = Instant.now().toEpochMilli();

        @SuppressWarnings("unchecked")
        List<String> claimedIds = redisTemplate.execute(
                claimScript,
                Collections.singletonList(DELAY_QUEUE_KEY),
                String.valueOf(nowMs));

        if (claimedIds == null || claimedIds.isEmpty()) {
            return;
        }

        log.info("DelayScheduler: claimed {} step(s) from delay queue", claimedIds.size());

        for (String stepIdStr : claimedIds) {
            processDelayedStep(UUID.fromString(stepIdStr));
        }
    }

    private void processDelayedStep(UUID stepExecutionId) {
        StepExecution step = stepExecutionRepository.findById(stepExecutionId).orElse(null);
        if (step == null) {
            log.warn("Delayed step not found: {}", stepExecutionId);
            return;
        }

        if (step.getStatus() != StepStatus.RETRY_SCHEDULED) {
            log.info("Delayed step {} not in RETRY_SCHEDULED state ({}), skipping",
                    stepExecutionId, step.getStatus());
            return;
        }

        stepStateMachine.validate(step.getStatus(), StepStatus.PENDING);
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

        log.info("Delayed step {} transitioned RETRY_SCHEDULED -> PENDING, outbox event created",
                step.getStepExecutionId());
    }

    /**
     * Schedule a step to be woken up after a delay.
     * Used by StepResultProcessor for DELAY_REQUESTED outcomes.
     */
    public void schedule(UUID stepExecutionId, long wakeUpEpochMillis) {
        redisTemplate.opsForZSet().add(
                DELAY_QUEUE_KEY,
                stepExecutionId.toString(),
                wakeUpEpochMillis);
        log.debug("Scheduled step {} in delay queue for {}", stepExecutionId, wakeUpEpochMillis);
    }
}
