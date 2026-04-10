package com.atlas.identity.event;

import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Polls the outbox table and publishes events to Kafka.
 *
 * <p>Each event is processed in its own transaction so that a Kafka send failure
 * for one event does not roll back the published status of previously sent events
 * in the same batch. This provides an <strong>at-least-once</strong> delivery guarantee:
 * if the process crashes after Kafka send but before the DB commit, the event will
 * be re-sent on the next poll cycle. Consumers must be idempotent.</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Map<String, Object>> kafkaTemplate,
                           TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEvent> batch = outboxRepository.findUnpublishedBatch(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getTenantId().toString(), event.getPayload())
                        .get();
                transactionTemplate.executeWithoutResult(status -> {
                    event.markPublished();
                    outboxRepository.save(event);
                });
                log.debug("Published outbox event {} of type {} to topic {}",
                        event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} of type {} to topic {}: {}",
                        event.getId(), event.getEventType(), event.getTopic(), e.getMessage());
                break; // stop processing batch on failure to preserve ordering
            }
        }
    }
}
