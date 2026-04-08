package com.atlas.identity.event;

import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Map<String, Object>> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedRate = 500)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> events = outboxRepository.findUnpublishedOrderedByAggregateAndCreatedAt();
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getTenantId().toString(), event.getPayload())
                        .get();
                event.markPublished();
                outboxRepository.save(event);
                log.debug("Published outbox event {} of type {} to topic {}",
                        event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} of type {} to topic {}: {}",
                        event.getId(), event.getEventType(), event.getTopic(), e.getMessage());
                break;
            }
        }
    }
}
