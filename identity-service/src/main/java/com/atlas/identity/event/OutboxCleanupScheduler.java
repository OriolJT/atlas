package com.atlas.identity.event;

import com.atlas.identity.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final OutboxRepository outboxRepository;

    public OutboxCleanupScheduler(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        outboxRepository.deletePublishedBefore(cutoff);
        log.debug("Cleaned up published outbox events older than {}", cutoff);
    }
}
