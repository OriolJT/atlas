package com.atlas.audit.service;

import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Query service for audit events. Builds JPQL queries dynamically based on
 * provided optional filters. Results are always tenant-scoped and ordered
 * newest-first. Page size defaults to 20 and is capped at 100.
 */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditEventRepository repository;
    private final EntityManager em;

    public AuditQueryService(AuditEventRepository repository, EntityManager em) {
        this.repository = repository;
        this.em = em;
    }

    /**
     * Returns up to {@code size} audit events for the given tenant, filtered by the
     * supplied optional parameters. All parameters except {@code tenantId} are optional.
     *
     * @param tenantId     required – the tenant whose events are queried
     * @param eventType    optional filter on event_type
     * @param resourceType optional filter on resource_type
     * @param resourceId   optional filter on resource_id
     * @param actorId      optional filter on actor_id
     * @param from         optional lower bound (inclusive) on occurred_at
     * @param to           optional upper bound (inclusive) on occurred_at
     * @param size         page size; defaults to {@value DEFAULT_PAGE_SIZE}, capped at {@value MAX_PAGE_SIZE}
     */
    public List<AuditEvent> findByTenant(
            UUID tenantId,
            String eventType,
            String resourceType,
            UUID resourceId,
            UUID actorId,
            Instant from,
            Instant to,
            Integer size) {

        int limit = resolveSize(size);

        StringBuilder jpql = new StringBuilder(
                "SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId");

        if (eventType != null) {
            jpql.append(" AND e.eventType = :eventType");
        }
        if (resourceType != null) {
            jpql.append(" AND e.resourceType = :resourceType");
        }
        if (resourceId != null) {
            jpql.append(" AND e.resourceId = :resourceId");
        }
        if (actorId != null) {
            jpql.append(" AND e.actorId = :actorId");
        }
        if (from != null) {
            jpql.append(" AND e.occurredAt >= :from");
        }
        if (to != null) {
            jpql.append(" AND e.occurredAt <= :to");
        }

        jpql.append(" ORDER BY e.occurredAt DESC, e.auditEventId DESC");

        TypedQuery<AuditEvent> query = em.createQuery(jpql.toString(), AuditEvent.class);
        query.setParameter("tenantId", tenantId);
        query.setMaxResults(limit);

        if (eventType != null) {
            query.setParameter("eventType", eventType);
        }
        if (resourceType != null) {
            query.setParameter("resourceType", resourceType);
        }
        if (resourceId != null) {
            query.setParameter("resourceId", resourceId);
        }
        if (actorId != null) {
            query.setParameter("actorId", actorId);
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }

        return query.getResultList();
    }

    private int resolveSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
