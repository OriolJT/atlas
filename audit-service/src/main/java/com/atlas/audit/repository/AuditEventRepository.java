package com.atlas.audit.repository;

import com.atlas.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    // Queries are built dynamically via AuditQueryService using EntityManager
}
