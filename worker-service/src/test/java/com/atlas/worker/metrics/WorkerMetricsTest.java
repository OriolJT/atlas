package com.atlas.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerMetricsTest {

    private SimpleMeterRegistry registry;
    private WorkerMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(registry);
    }

    @Test
    void recordStepSuccess_incrementsCounterWithStepTypeTag() {
        metrics.recordStepSuccess("HTTP_ACTION");
        metrics.recordStepSuccess("HTTP_ACTION");
        metrics.recordStepSuccess("INTERNAL_COMMAND");

        Counter httpCounter = registry.find("atlas.worker.step.success")
                .tag("step_type", "HTTP_ACTION")
                .counter();
        Counter internalCounter = registry.find("atlas.worker.step.success")
                .tag("step_type", "INTERNAL_COMMAND")
                .counter();

        assertThat(httpCounter).isNotNull();
        assertThat(httpCounter.count()).isEqualTo(2.0);
        assertThat(internalCounter).isNotNull();
        assertThat(internalCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordStepFailure_incrementsCounterWithStepTypeTag() {
        metrics.recordStepFailure("HTTP_ACTION");

        Counter counter = registry.find("atlas.worker.step.failure")
                .tag("step_type", "HTTP_ACTION")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordStepDuration_recordsTimerWithStepTypeTag() {
        metrics.recordStepDuration("HTTP_ACTION", Duration.ofMillis(250));
        metrics.recordStepDuration("HTTP_ACTION", Duration.ofMillis(750));

        Timer timer = registry.find("atlas.worker.step.duration")
                .tag("step_type", "HTTP_ACTION")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(1000.0);
    }

    @Test
    void recordLeaseAcquired_incrementsCounter() {
        metrics.recordLeaseAcquired();
        metrics.recordLeaseAcquired();

        Counter counter = registry.find("atlas.worker.lease.acquired").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordLeaseConflict_incrementsCounter() {
        metrics.recordLeaseConflict();

        Counter counter = registry.find("atlas.worker.lease.conflict").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void initialState_leaseMetersPreRegistered() {
        // Lease meters are pre-registered in the constructor (no tags required)
        assertThat(registry.find("atlas.worker.lease.acquired").counter()).isNotNull();
        assertThat(registry.find("atlas.worker.lease.conflict").counter()).isNotNull();

        // Step meters are tag-based (step_type) and registered lazily on first use
        assertThat(registry.find("atlas.worker.step.duration").timer()).isNull();
        assertThat(registry.find("atlas.worker.step.success").counter()).isNull();
        assertThat(registry.find("atlas.worker.step.failure").counter()).isNull();
    }
}
