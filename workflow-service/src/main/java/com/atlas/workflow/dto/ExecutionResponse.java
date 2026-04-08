package com.atlas.workflow.dto;

import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.WorkflowExecution;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionResponse(
        UUID executionId,
        UUID tenantId,
        UUID definitionId,
        String idempotencyKey,
        ExecutionStatus status,
        Map<String, Object> inputJson,
        Map<String, Object> outputJson,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    public static ExecutionResponse from(WorkflowExecution entity) {
        return new ExecutionResponse(
                entity.getExecutionId(),
                entity.getTenantId(),
                entity.getDefinitionId(),
                entity.getIdempotencyKey(),
                entity.getStatus(),
                entity.getInputJson(),
                entity.getOutputJson(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }
}
