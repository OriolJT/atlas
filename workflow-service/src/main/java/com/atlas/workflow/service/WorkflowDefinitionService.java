package com.atlas.workflow.service;

import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.repository.WorkflowDefinitionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
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
    public WorkflowDefinition getById(UUID definitionId) {
        return repository.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + definitionId));
    }

    @Transactional
    public WorkflowDefinition publish(UUID definitionId) {
        WorkflowDefinition definition = repository.findById(definitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow definition not found: " + definitionId));

        definition.publish();
        return repository.save(definition);
    }
}
