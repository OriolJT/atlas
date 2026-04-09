package com.atlas.worker.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InFlightTrackerTest {

    private InFlightTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
    }

    @Test
    void startTracking_incrementsCount() {
        tracker.startTracking("step-1");
        tracker.startTracking("step-2");

        assertThat(tracker.getInFlightCount()).isEqualTo(2);
    }

    @Test
    void stopTracking_decrementsCount() {
        tracker.startTracking("step-1");
        tracker.startTracking("step-2");
        tracker.stopTracking("step-1");

        assertThat(tracker.getInFlightCount()).isEqualTo(1);
    }

    @Test
    void awaitDrain_returnsTrueWhenAlreadyEmpty() {
        boolean result = tracker.awaitDrain(1);

        assertThat(result).isTrue();
    }

    @Test
    void awaitDrain_returnsTrueWhenStepCompletesBeforeTimeout() throws Exception {
        tracker.startTracking("step-1");

        Future<Boolean> future = Executors.newSingleThreadExecutor().submit(() ->
                tracker.awaitDrain(5)
        );

        // Stop tracking after a short delay — well within the 5-second timeout
        Thread.sleep(100);
        tracker.stopTracking("step-1");

        assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void awaitDrain_returnsFalseOnTimeoutWhenStepsStillInFlight() {
        tracker.startTracking("step-stuck");

        boolean result = tracker.awaitDrain(1);

        assertThat(result).isFalse();
        assertThat(tracker.getInFlightCount()).isEqualTo(1);
    }
}
