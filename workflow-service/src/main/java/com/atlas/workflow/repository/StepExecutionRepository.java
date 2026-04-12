package com.atlas.workflow.repository;

import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {

    List<StepExecution> findByExecutionIdOrderByStepIndex(UUID executionId);

    List<StepExecution> findByTenantIdAndStatus(UUID tenantId, StepStatus status);

    boolean existsByExecutionIdAndStatus(UUID executionId, StepStatus status);

    @Query("SELECT s FROM StepExecution s WHERE s.status = com.atlas.workflow.domain.StepStatus.RETRY_SCHEDULED AND s.nextRetryAt <= :now")
    List<StepExecution> findDueForRetry(@Param("now") Instant now, Pageable pageable);

    @Query("SELECT s FROM StepExecution s WHERE s.status IN (com.atlas.workflow.domain.StepStatus.RUNNING, com.atlas.workflow.domain.StepStatus.LEASED)")
    List<StepExecution> findRunningOrLeased(Pageable pageable);
}
