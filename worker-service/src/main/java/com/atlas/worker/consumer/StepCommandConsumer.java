package com.atlas.worker.consumer;

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

    @KafkaListener(topics = "workflow.steps.execute", groupId = "worker-service")
    @SuppressWarnings("unchecked")
    public void onStepCommand(Map<String, Object> payload) {
        StepCommand command = parseCommand(payload);

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
        return new StepCommand(
                (String) payload.get("step_execution_id"),
                (String) payload.get("execution_id"),
                (String) payload.get("tenant_id"),
                (String) payload.get("step_name"),
                (String) payload.get("step_type"),
                ((Number) payload.get("attempt")).intValue(),
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
