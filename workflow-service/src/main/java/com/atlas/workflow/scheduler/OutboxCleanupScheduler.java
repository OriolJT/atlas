package com.atlas.workflow.scheduler;

import com.atlas.workflow.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);
    private static final long RETENTION_HOURS = 24;

    private final OutboxRepository outboxRepository;

    public OutboxCleanupScheduler(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanupPublishedEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        int deleted = outboxRepository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("OutboxCleanupScheduler: deleted {} published outbox event(s) older than {}h",
                    deleted, RETENTION_HOURS);
        }
    }
}
