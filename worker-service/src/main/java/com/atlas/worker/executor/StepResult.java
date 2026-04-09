package com.atlas.worker.executor;

import java.util.Map;

public record StepResult(
        String stepExecutionId,
        StepOutcome outcome,
        int attempt,
        Map<String, Object> output,
        String error,
        boolean nonRetryable
) {

    public static StepResult success(String stepExecutionId, int attempt, Map<String, Object> output) {
        return new StepResult(stepExecutionId, StepOutcome.SUCCEEDED, attempt, output, null, false);
    }

    public static StepResult failure(String stepExecutionId, int attempt, String error, boolean nonRetryable) {
        return new StepResult(stepExecutionId, StepOutcome.FAILED, attempt, null, error, nonRetryable);
    }

    public static StepResult delayRequested(String stepExecutionId, int attempt, long delayMs) {
        return new StepResult(stepExecutionId, StepOutcome.DELAY_REQUESTED, attempt,
                Map.of("delay_ms", delayMs), null, false);
    }

    public static StepResult waiting(String stepExecutionId, int attempt) {
        return new StepResult(stepExecutionId, StepOutcome.WAITING, attempt, null, null, false);
    }
}
