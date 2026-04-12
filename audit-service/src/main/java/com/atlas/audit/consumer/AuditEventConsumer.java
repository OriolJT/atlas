package com.atlas.audit.consumer;

import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.repository.AuditEventRepository;
import com.atlas.common.event.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes audit events from the "audit.events" Kafka topic and persists them.
 * Processing is idempotent: duplicate audit_event_id values are silently ignored.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditEventRepository repository;

    public AuditEventConsumer(AuditEventRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = EventTypes.TOPIC_AUDIT_EVENTS, groupId = "audit-service")
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public void onAuditEvent(Map<String, Object> payload) {
        AuditEvent event;
        try {
            event = parseEvent(payload);
        } catch (Exception e) {
            log.error("Failed to parse audit event from Kafka payload, skipping poison pill message. Payload: {}. Error: {}",
                    payload, e.getMessage(), e);
            return;
        }
        log.debug("Received audit event: auditEventId={} eventType={} tenantId={}",
                event.getAuditEventId(), event.getEventType(), event.getTenantId());
        try {
            repository.save(event);
            log.info("Saved audit event: auditEventId={} eventType={} tenantId={}",
                    event.getAuditEventId(), event.getEventType(), event.getTenantId());
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate audit event ignored: auditEventId={}", event.getAuditEventId());
        } catch (Exception e) {
            log.error("Failed to save audit event: auditEventId={}, skipping. Error: {}",
                    event.getAuditEventId(), e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private AuditEvent parseEvent(Map<String, Object> payload) {
        UUID auditEventId = parseUuid(payload.getOrDefault("audit_event_id",
                payload.get("event_id")));
        UUID tenantId = parseUuid(payload.get("tenant_id"));
        String actorType = payload.get("actor_type") != null ? (String) payload.get("actor_type") : "SYSTEM";
        UUID actorId = parseUuidOrNull(payload.get("actor_id"));
        String eventType = (String) payload.get("event_type");
        String resourceType = payload.get("resource_type") != null ? (String) payload.get("resource_type") : "UNKNOWN";
        UUID resourceId = parseUuidOrNull(payload.get("resource_id"));
        Map<String, Object> eventPayload = (Map<String, Object>) payload.getOrDefault("payload", Map.of());
        UUID correlationId = parseUuidOrNull(payload.get("correlation_id"));
        Instant occurredAt = parseInstant(payload.get("occurred_at"));

        return new AuditEvent(
                auditEventId,
                tenantId,
                actorType,
                actorId,
                eventType,
                resourceType,
                resourceId,
                eventPayload,
                correlationId,
                occurredAt
        );
    }

    private UUID parseUuid(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Required UUID field is null in audit event payload");
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private UUID parseUuidOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String str = value.toString();
        return str.isBlank() ? null : UUID.fromString(str);
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Required occurred_at field is null in audit event payload");
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        return Instant.parse(value.toString());
    }
}
