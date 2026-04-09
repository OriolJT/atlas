package com.atlas.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Central registry of business metrics for the worker service.
 *
 * <p>Meters exposed:
 * <ul>
 *   <li>{@code atlas.worker.step.duration} — execution time per step type</li>
 *   <li>{@code atlas.worker.step.success} — successful step executions per step type</li>
 *   <li>{@code atlas.worker.step.failure} — failed step executions per step type</li>
 *   <li>{@code atlas.worker.lease.acquired} — leases successfully acquired</li>
 *   <li>{@code atlas.worker.lease.conflict} — lease acquisition conflicts (duplicate delivery)</li>
 * </ul>
 */
@Component
public class WorkerMetrics {

    private final MeterRegistry registry;
    private final Timer stepDurationTimer;
    private final Counter stepSuccessCounter;
    private final Counter stepFailureCounter;
    private final Counter leaseAcquiredCounter;
    private final Counter leaseConflictCounter;

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.stepDurationTimer = Timer.builder("atlas.worker.step.duration")
                .description("Execution duration of worker steps")
                .register(registry);

        this.stepSuccessCounter = Counter.builder("atlas.worker.step.success")
                .description("Number of successfully completed worker steps")
                .register(registry);

        this.stepFailureCounter = Counter.builder("atlas.worker.step.failure")
                .description("Number of failed worker step executions")
                .register(registry);

        this.leaseAcquiredCounter = Counter.builder("atlas.worker.lease.acquired")
                .description("Number of Redis leases successfully acquired")
                .register(registry);

        this.leaseConflictCounter = Counter.builder("atlas.worker.lease.conflict")
                .description("Number of Redis lease acquisition conflicts (duplicate delivery)")
                .register(registry);
    }

    /**
     * Records the execution duration of a step, tagged with the step type.
     */
    public void recordStepDuration(String stepType, Duration duration) {
        Timer.builder("atlas.worker.step.duration")
                .description("Execution duration of worker steps")
                .tag("step_type", stepType)
                .register(registry)
                .record(duration);
    }

    /**
     * Increments the success counter for the given step type.
     */
    public void recordStepSuccess(String stepType) {
        Counter.builder("atlas.worker.step.success")
                .description("Number of successfully completed worker steps")
                .tag("step_type", stepType)
                .register(registry)
                .increment();
    }

    /**
     * Increments the failure counter for the given step type.
     */
    public void recordStepFailure(String stepType) {
        Counter.builder("atlas.worker.step.failure")
                .description("Number of failed worker step executions")
                .tag("step_type", stepType)
                .register(registry)
                .increment();
    }

    /**
     * Increments the lease-acquired counter.
     */
    public void recordLeaseAcquired() {
        leaseAcquiredCounter.increment();
    }

    /**
     * Increments the lease-conflict counter.
     */
    public void recordLeaseConflict() {
        leaseConflictCounter.increment();
    }
}
