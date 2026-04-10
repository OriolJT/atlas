package com.atlas.worker.publisher;

import com.atlas.common.event.EventTypes;
import com.atlas.worker.executor.StepResult;
import com.atlas.worker.grpc.StepResultGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Publishes {@link StepResult} messages to the Workflow Service.
 *
 * <p>Supports two transports:
 * <ul>
 *   <li><b>Kafka</b> (default) — publishes to the {@code workflow.steps.result} topic</li>
 *   <li><b>gRPC</b> (opt-in) — sends directly to the Workflow Service gRPC server</li>
 * </ul>
 *
 * <p>The transport is selected based on whether a {@link StepResultGrpcClient} bean is
 * present in the context (configured via {@code atlas.worker.result-transport=grpc}).
 *
 * <p>Uses synchronous send for both transports to guarantee delivery before the caller
 * releases the Redis lease. This ensures at-least-once result delivery.
 */
@Component
public class StepResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(StepResultPublisher.class);
    private static final String TOPIC = EventTypes.TOPIC_STEP_RESULT;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StepResultGrpcClient grpcClient;

    public StepResultPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               @Autowired(required = false) StepResultGrpcClient grpcClient) {
        this.kafkaTemplate = kafkaTemplate;
        this.grpcClient = grpcClient;

        if (grpcClient != null) {
            log.info("Step result transport: gRPC (Kafka available as fallback)");
        } else {
            log.info("Step result transport: Kafka");
        }
    }

    /**
     * Publishes the given step result using the configured transport.
     *
     * @param result   the execution result
     * @param tenantId the tenant identifier, used as the Kafka message key for partition affinity
     */
    public void publish(StepResult result, String tenantId) {
        if (grpcClient != null) {
            publishViaGrpc(result, tenantId);
        } else {
            publishViaKafka(result, tenantId);
        }
    }

    private void publishViaGrpc(StepResult result, String tenantId) {
        try {
            grpcClient.reportResult(result, tenantId);
            log.debug("Published result via gRPC for stepExecutionId={} outcome={}",
                    result.stepExecutionId(), result.outcome());
        } catch (Exception e) {
            log.warn("gRPC publish failed for stepExecutionId={}, falling back to Kafka: {}",
                    result.stepExecutionId(), e.getMessage());
            publishViaKafka(result, tenantId);
        }
    }

    private void publishViaKafka(StepResult result, String tenantId) {
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
            log.debug("Published result via Kafka for stepExecutionId={} outcome={}",
                    result.stepExecutionId(), result.outcome());
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to publish step result for " + result.stepExecutionId(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing step result for " + result.stepExecutionId(), e);
        }
    }
}
