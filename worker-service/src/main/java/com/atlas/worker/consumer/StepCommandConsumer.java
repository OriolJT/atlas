package com.atlas.worker.consumer;

import com.atlas.common.event.EventTypes;
import com.atlas.worker.executor.StepCommand;
import com.atlas.worker.executor.StepExecutor;
import com.atlas.worker.executor.StepExecutorRegistry;
import com.atlas.worker.executor.StepResult;
import com.atlas.worker.lease.LeaseManager;
import com.atlas.worker.lifecycle.InFlightTracker;
import com.atlas.worker.metrics.WorkerMetrics;
import com.atlas.worker.publisher.StepResultPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka consumer that receives step execution commands, acquires a Redis lease to prevent
 * duplicate processing, delegates execution to the appropriate {@link StepExecutor}, and
 * publishes the result back to Kafka.
 */
@Component
public class StepCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(StepCommandConsumer.class);
    private static final long DEFAULT_LEASE_TIMEOUT_SECONDS = 60;

    private final LeaseManager leaseManager;
    private final StepExecutorRegistry executorRegistry;
    private final StepResultPublisher resultPublisher;
    private final InFlightTracker inFlightTracker;
    private final WorkerMetrics metrics;
    private final String workerId;

    public StepCommandConsumer(LeaseManager leaseManager,
                               StepExecutorRegistry executorRegistry,
                               StepResultPublisher resultPublisher,
                               InFlightTracker inFlightTracker,
                               WorkerMetrics metrics) {
        this.leaseManager = leaseManager;
        this.executorRegistry = executorRegistry;
        this.resultPublisher = resultPublisher;
        this.inFlightTracker = inFlightTracker;
        this.metrics = metrics;
        this.workerId = resolveWorkerId();
    }

    @KafkaListener(topics = EventTypes.TOPIC_STEP_EXECUTE, groupId = "worker-service")
    public void onStepCommand(Map<String, Object> payload) {
        StepCommand command;
        try {
            command = parseCommand(payload);
        } catch (IllegalArgumentException e) {
            log.error("Dropping poison message: {}", e.getMessage());
            // Publish a non-retryable FAILED result if we can extract enough info
            String stepExecutionId = payload.get("step_execution_id") != null
                    ? payload.get("step_execution_id").toString() : null;
            String tenantId = payload.get("tenant_id") != null
                    ? payload.get("tenant_id").toString() : null;
            if (stepExecutionId != null && tenantId != null) {
                StepResult failedResult = StepResult.failure(stepExecutionId, 0, e.getMessage(), true);
                resultPublisher.publish(failedResult, tenantId);
            }
            return;
        }

        log.info("Received step command: stepExecutionId={} stepType={} attempt={}",
                command.stepExecutionId(), command.stepType(), command.attempt());

        long leaseTimeoutSeconds = Math.max(command.timeoutMs() / 1000, DEFAULT_LEASE_TIMEOUT_SECONDS);

        if (!leaseManager.acquireLease(command.stepExecutionId(), workerId, leaseTimeoutSeconds)) {
            log.info("Lease already held for stepExecutionId={}, skipping", command.stepExecutionId());
            metrics.recordLeaseConflict();
            return;
        }

        metrics.recordLeaseAcquired();
        inFlightTracker.startTracking(command.stepExecutionId());
        Instant start = Instant.now();
        try {
            StepExecutor executor = executorRegistry.getExecutor(command.stepType());
            StepResult result = executor.execute(command);
            resultPublisher.publish(result, command.tenantId());

            Duration duration = Duration.between(start, Instant.now());
            metrics.recordStepSuccess(command.stepType());
            metrics.recordStepDuration(command.stepType(), duration);

            log.info("Step completed: stepExecutionId={} outcome={}", command.stepExecutionId(), result.outcome());
        } catch (Exception e) {
            log.error("Step execution failed: stepExecutionId={}", command.stepExecutionId(), e);

            metrics.recordStepFailure(command.stepType());

            StepResult failedResult = StepResult.failure(
                    command.stepExecutionId(),
                    command.attempt(),
                    e.getMessage(),
                    false
            );
            resultPublisher.publish(failedResult, command.tenantId());
        } finally {
            inFlightTracker.stopTracking(command.stepExecutionId());
            leaseManager.releaseLease(command.stepExecutionId(), workerId);
        }
    }

    @SuppressWarnings("unchecked")
    private StepCommand parseCommand(Map<String, Object> payload) {
        String stepExecutionId = (String) payload.get("step_execution_id");
        String executionId = (String) payload.get("execution_id");
        String tenantId = (String) payload.get("tenant_id");
        String stepName = (String) payload.get("step_name");
        String stepType = (String) payload.get("step_type");

        if (stepExecutionId == null || executionId == null || tenantId == null
                || stepName == null || stepType == null) {
            throw new IllegalArgumentException(
                    "Poison message: missing required field(s) in step command — "
                    + "step_execution_id=" + stepExecutionId
                    + " execution_id=" + executionId
                    + " tenant_id=" + tenantId
                    + " step_name=" + stepName
                    + " step_type=" + stepType);
        }

        Number attemptNum = (Number) payload.get("attempt");
        int attempt = attemptNum != null ? attemptNum.intValue() : 1;

        return new StepCommand(
                stepExecutionId,
                executionId,
                tenantId,
                stepName,
                stepType,
                attempt,
                (Map<String, Object>) payload.getOrDefault("input", Map.of()),
                ((Number) payload.getOrDefault("timeout_ms", 30000)).longValue(),
                Boolean.TRUE.equals(payload.get("is_compensation")),
                (String) payload.get("compensation_for")
        );
    }

    private static String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
