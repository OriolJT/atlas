package com.atlas.workflow.repository;

import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {

    List<StepExecution> findByExecutionIdOrderByStepIndex(UUID executionId);

    List<StepExecution> findByTenantIdAndStatus(UUID tenantId, StepStatus status);

    boolean existsByExecutionIdAndStatus(UUID executionId, StepStatus status);
}
