package com.atlas.worker.executor;

import java.util.Map;

public record StepCommand(
        String stepExecutionId,
        String executionId,
        String tenantId,
        String stepName,
        String stepType,
        int attempt,
        Map<String, Object> input,
        long timeoutMs,
        boolean isCompensation,
        String compensationFor
) {}
