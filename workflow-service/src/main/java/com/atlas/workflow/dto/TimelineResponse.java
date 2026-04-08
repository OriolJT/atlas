package com.atlas.workflow.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TimelineResponse(
        UUID executionId,
        String status,
        List<TimelineEvent> events
) {
    public record TimelineEvent(
            String timestamp,
            String type,
            String stepName,
            int attempt,
            Map<String, Object> detail
    ) {}
}
