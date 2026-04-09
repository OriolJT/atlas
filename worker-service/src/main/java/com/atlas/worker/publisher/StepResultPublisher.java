package com.atlas.worker.publisher;

import com.atlas.common.event.EventTypes;
import com.atlas.worker.executor.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Publishes {@link StepResult} messages to the {@code workflow.steps.result} Kafka topic.
 *
 * <p>Uses synchronous send (blocking on the future) to guarantee delivery before the caller
 * releases the Redis lease. This ensures at-least-once result delivery.
 */
@Component
public class StepResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(StepResultPublisher.class);
    private static final String TOPIC = EventTypes.TOPIC_STEP_RESULT;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public StepResultPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes the given step result to the result topic.
     *
     * @param result   the execution result
     * @param tenantId the tenant identifier, used as the Kafka message key for partition affinity
     */
    public void publish(StepResult result, String tenantId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step_execution_id", result.stepExecutionId());
        payload.put("outcome", result.outcome().name());
        payload.put("attempt", result.attempt());
        if (result.output() != null) {
            payload.put("output", result.output());
        }
        if (result.error() != null) {
            payload.put("error", result.error());
        }
        payload.put("non_retryable", result.nonRetryable());

        try {
            kafkaTemplate.send(TOPIC, tenantId, payload).get();
            log.debug("Published result for stepExecutionId={} outcome={}", result.stepExecutionId(), result.outcome());
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to publish step result for " + result.stepExecutionId(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing step result for " + result.stepExecutionId(), e);
        }
    }
}
