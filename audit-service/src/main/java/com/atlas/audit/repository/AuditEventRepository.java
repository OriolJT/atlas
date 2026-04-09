package com.atlas.audit.repository;

import com.atlas.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Cursor-based page: events for a tenant that occurred before a given cursor timestamp,
     * ordered newest-first, limited to {@code limit} results.
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.tenantId = :tenantId
              AND e.occurredAt < :cursor
            ORDER BY e.occurredAt DESC, e.auditEventId DESC
            LIMIT :limit
            """)
    List<AuditEvent> findByTenantIdBefore(
            @Param("tenantId") UUID tenantId,
            @Param("cursor") Instant cursor,
            @Param("limit") int limit);

    /**
     * Cursor-based page filtered by event type.
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.tenantId = :tenantId
              AND e.eventType = :eventType
              AND e.occurredAt < :cursor
            ORDER BY e.occurredAt DESC, e.auditEventId DESC
            LIMIT :limit
            """)
    List<AuditEvent> findByTenantIdAndEventTypeBefore(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType,
            @Param("cursor") Instant cursor,
            @Param("limit") int limit);

    /**
     * Cursor-based page filtered by resource type and optional resource id.
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.tenantId = :tenantId
              AND e.resourceType = :resourceType
              AND (:resourceId IS NULL OR e.resourceId = :resourceId)
              AND e.occurredAt < :cursor
            ORDER BY e.occurredAt DESC, e.auditEventId DESC
            LIMIT :limit
            """)
    List<AuditEvent> findByTenantIdAndResourceBefore(
            @Param("tenantId") UUID tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("cursor") Instant cursor,
            @Param("limit") int limit);
}
