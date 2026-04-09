package com.atlas.worker.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks step executions that are currently in-flight. Used by {@link GracefulShutdownHandler}
 * to wait for all executing steps to complete before the worker shuts down.
 */
@Component
public class InFlightTracker {

    private static final Logger log = LoggerFactory.getLogger(InFlightTracker.class);
    private static final long POLL_INTERVAL_MS = 200;

    private final ConcurrentHashMap<String, Instant> inFlight = new ConcurrentHashMap<>();

    /**
     * Records that execution of the given step has started.
     *
     * @param stepExecutionId the unique step execution identifier
     */
    public void startTracking(String stepExecutionId) {
        inFlight.put(stepExecutionId, Instant.now());
        log.debug("Tracking started: stepExecutionId={} inFlightCount={}", stepExecutionId, inFlight.size());
    }

    /**
     * Records that execution of the given step has finished.
     *
     * @param stepExecutionId the unique step execution identifier
     */
    public void stopTracking(String stepExecutionId) {
        inFlight.remove(stepExecutionId);
        log.debug("Tracking stopped: stepExecutionId={} inFlightCount={}", stepExecutionId, inFlight.size());
    }

    /**
     * Returns the number of steps currently being executed.
     *
     * @return current in-flight step count
     */
    public int getInFlightCount() {
        return inFlight.size();
    }

    /**
     * Blocks until all in-flight steps have completed or the timeout elapses.
     *
     * @param timeoutSeconds maximum number of seconds to wait
     * @return {@code true} if all steps drained within the timeout; {@code false} if timed out
     */
    public boolean awaitDrain(long timeoutSeconds) {
        long deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000;

        while (!inFlight.isEmpty()) {
            if (System.currentTimeMillis() >= deadlineMs) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
