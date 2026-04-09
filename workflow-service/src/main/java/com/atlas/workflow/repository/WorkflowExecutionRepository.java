package com.atlas.workflow.repository;

import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Optional<WorkflowExecution> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<WorkflowExecution> findByTenantIdAndStatus(UUID tenantId, ExecutionStatus status);

    List<WorkflowExecution> findByDefinitionId(UUID definitionId);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);

    @Query("SELECT COUNT(e) FROM WorkflowExecution e WHERE e.tenantId = :tenantId AND e.status IN :statuses")
    long countByTenantIdAndStatusIn(UUID tenantId, List<ExecutionStatus> statuses);
}
