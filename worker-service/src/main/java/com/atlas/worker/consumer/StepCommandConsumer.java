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

import org.springframework.beans.factory.annotation.Value;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService leaseRenewalScheduler;

    public StepCommandConsumer(LeaseManager leaseManager,
                               StepExecutorRegistry executorRegistry,
                               StepResultPublisher resultPublisher,
                               InFlightTracker inFlightTracker,
                               WorkerMetrics metrics,
                               @Value("${atlas.worker.concurrency:4}") int concurrency) {
        this.leaseManager = leaseManager;
        this.executorRegistry = executorRegistry;
        this.resultPublisher = resultPublisher;
        this.inFlightTracker = inFlightTracker;
        this.metrics = metrics;
        this.workerId = resolveWorkerId();
        executorRegistry.getKnownStepTypes().forEach(metrics::registerKnownStepType);
        this.leaseRenewalScheduler = Executors.newScheduledThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "lease-renewal");
            t.setDaemon(true);
            return t;
        });
    }

    @KafkaListener(topics = EventTypes.TOPIC_STEP_EXECUTE, groupId = "worker-service",
            concurrency = "${atlas.worker.concurrency:4}")
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

        // Schedule recurring lease renewal at TTL/3 intervals
        long renewalIntervalSeconds = Math.max(leaseTimeoutSeconds / 3, 1);
        ScheduledFuture<?> leaseRenewalFuture = leaseRenewalScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        boolean extended = leaseManager.extendLease(
                                command.stepExecutionId(), workerId, leaseTimeoutSeconds);
                        if (!extended) {
                            log.warn("Failed to extend lease for stepExecutionId={}", command.stepExecutionId());
                        }
                    } catch (Exception ex) {
                        log.warn("Lease renewal error for stepExecutionId={}", command.stepExecutionId(), ex);
                    }
                },
                renewalIntervalSeconds,
                renewalIntervalSeconds,
                TimeUnit.SECONDS
        );

        boolean resultPublished = false;
        Instant start = Instant.now();
        try {
            StepExecutor executor = executorRegistry.getExecutor(command.stepType());
            StepResult result = executor.execute(command);
            resultPublisher.publish(result, command.tenantId());
            resultPublished = true;

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
            try {
                resultPublisher.publish(failedResult, command.tenantId());
                resultPublished = true;
            } catch (Exception publishEx) {
                log.error("Failed to publish failure result: stepExecutionId={} executionId={} tenantId={} attempt={} originalError={}",
                        command.stepExecutionId(), command.executionId(), command.tenantId(),
                        command.attempt(), e.getMessage(), publishEx);
            }
        } finally {
            leaseRenewalFuture.cancel(false);
            inFlightTracker.stopTracking(command.stepExecutionId());
            if (resultPublished) {
                leaseManager.releaseLease(command.stepExecutionId(), workerId);
            } else {
                log.warn("Lease NOT released for stepExecutionId={} because result was not published — "
                        + "lease will expire naturally, allowing retry",
                        command.stepExecutionId());
            }
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
            String hostname = System.getenv("HOSTNAME");
            if (hostname == null || hostname.isBlank()) {
                hostname = InetAddress.getLocalHost().getHostName();
            }
            return hostname + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
