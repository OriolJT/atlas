package com.atlas.workflow.dto;

import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.domain.WorkflowDefinition;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DefinitionResponse(
        UUID definitionId,
        UUID tenantId,
        String name,
        int version,
        DefinitionStatus status,
        Map<String, Object> stepsJson,
        Map<String, Object> compensationsJson,
        String triggerType,
        Instant createdAt,
        Instant publishedAt
) {

    public static DefinitionResponse from(WorkflowDefinition entity) {
        return new DefinitionResponse(
                entity.getDefinitionId(),
                entity.getTenantId(),
                entity.getName(),
                entity.getVersion(),
                entity.getStatus(),
                entity.getStepsJson(),
                entity.getCompensationsJson(),
                entity.getTriggerType(),
                entity.getCreatedAt(),
                entity.getPublishedAt()
        );
    }
}
