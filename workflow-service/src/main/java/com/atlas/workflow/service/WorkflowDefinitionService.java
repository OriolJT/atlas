package com.atlas.workflow.service;

import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.exception.ConflictException;
import com.atlas.workflow.exception.ResourceNotFoundException;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository repository;

    public WorkflowDefinitionService(WorkflowDefinitionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WorkflowDefinition create(UUID tenantId, CreateDefinitionRequest request) {
        if (repository.existsByTenantIdAndNameAndVersion(tenantId, request.name(), request.version())) {
            throw new ConflictException(
                    "Workflow definition with name '" + request.name()
                            + "' and version " + request.version() + " already exists for this tenant");
        }

        WorkflowDefinition definition = WorkflowDefinition.create(
                tenantId,
                request.name(),
                request.version(),
                request.stepsJson(),
                request.compensationsJson(),
                request.triggerType()
        );

        return repository.save(definition);
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition getById(UUID tenantId, UUID definitionId) {
        return repository.findByDefinitionIdAndTenantId(definitionId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Workflow definition not found: " + definitionId));
    }

    @Transactional
    public WorkflowDefinition publish(UUID tenantId, UUID definitionId) {
        WorkflowDefinition definition = repository.findByDefinitionIdAndTenantId(definitionId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Workflow definition not found: " + definitionId));

        definition.publish();
        return repository.save(definition);
    }
}
