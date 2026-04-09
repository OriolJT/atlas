package com.atlas.workflow.repository;

import com.atlas.workflow.domain.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    boolean existsByTenantIdAndNameAndVersion(UUID tenantId, String name, int version);

    Optional<WorkflowDefinition> findByDefinitionIdAndTenantId(UUID definitionId, UUID tenantId);

    long countByTenantId(UUID tenantId);
}
