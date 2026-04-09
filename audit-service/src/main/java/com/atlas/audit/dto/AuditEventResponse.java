package com.atlas.audit.dto;

import com.atlas.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID auditEventId,
        UUID tenantId,
        String actorType,
        UUID actorId,
        String eventType,
        String resourceType,
        UUID resourceId,
        Map<String, Object> payload,
        UUID correlationId,
        Instant occurredAt
) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getAuditEventId(),
                event.getTenantId(),
                event.getActorType(),
                event.getActorId(),
                event.getEventType(),
                event.getResourceType(),
                event.getResourceId(),
                event.getPayload(),
                event.getCorrelationId(),
                event.getOccurredAt()
        );
    }
}
