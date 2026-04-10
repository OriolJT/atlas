package com.atlas.workflow.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka consumer error handling configuration.
 *
 * <p>Failed messages are retried up to 3 times with 1-second intervals.
 * After exhausting retries, the message is published to a dead-letter topic
 * (original topic name + ".DLT" suffix) for manual inspection and replay.</p>
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long MAX_RETRIES = 3L;

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            KafkaOperations<String, Map<String, Object>> kafkaOperations) {

        var recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        var errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka retry attempt {} for topic={} key={}: {}",
                        deliveryAttempt, record.topic(), record.key(),
                        ex != null ? ex.getMessage() : "unknown"));

        return errorHandler;
    }
}
