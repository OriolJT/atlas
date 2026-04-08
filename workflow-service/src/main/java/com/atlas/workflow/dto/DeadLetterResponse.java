package com.atlas.workflow.dto;

import com.atlas.workflow.domain.DeadLetterItem;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeadLetterResponse(
        UUID deadLetterId,
        UUID tenantId,
        UUID executionId,
        UUID stepExecutionId,
        String stepName,
        String errorMessage,
        int attemptCount,
        Map<String, Object> payload,
        Instant createdAt,
        Instant replayedAt,
        boolean replayed
) {

    public static DeadLetterResponse from(DeadLetterItem item) {
        return new DeadLetterResponse(
                item.getDeadLetterId(),
                item.getTenantId(),
                item.getExecutionId(),
                item.getStepExecutionId(),
                item.getStepName(),
                item.getErrorMessage(),
                item.getAttemptCount(),
                item.getPayload(),
                item.getCreatedAt(),
                item.getReplayedAt(),
                item.isReplayed()
        );
    }
}
