package com.atlas.worker.lifecycle;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Handles graceful shutdown of the worker service by waiting for all in-flight step
 * executions to complete before the application context is destroyed.
 */
@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final InFlightTracker inFlightTracker;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    private final long drainTimeoutSeconds;

    public GracefulShutdownHandler(InFlightTracker inFlightTracker,
                                   KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
                                   @Value("${atlas.worker.drain-timeout-seconds:30}") long drainTimeoutSeconds) {
        this.inFlightTracker = inFlightTracker;
        this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
        this.drainTimeoutSeconds = drainTimeoutSeconds;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Initiating graceful shutdown — stopping Kafka consumers");
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(container -> container.stop());

        boolean drained = inFlightTracker.awaitDrain(drainTimeoutSeconds);

        if (drained) {
            log.info("Graceful shutdown complete");
        } else {
            int remaining = inFlightTracker.getInFlightCount();
            log.warn("Drain timeout exceeded, {} steps still in-flight", remaining);
        }
    }
}
