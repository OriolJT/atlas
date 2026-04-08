package com.atlas.workflow.service;

import com.atlas.workflow.domain.OutboxEvent;
import com.atlas.workflow.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Map<String, Object>> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findUnpublishedBatch(PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(
                        event.getTopic(),
                        event.getAggregateId().toString(),
                        event.getPayload()
                ).get(); // synchronous send

                event.markPublished();
                outboxRepository.save(event);

                log.debug("Published outbox event {} to topic {}", event.getId(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage(), e);
                break; // stop processing batch on failure to preserve ordering
            }
        }
    }
}
