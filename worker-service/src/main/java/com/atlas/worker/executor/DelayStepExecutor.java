package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

/**
 * Executor for steps of type DELAY.
 *
 * <p>Returns {@link StepOutcome#DELAY_REQUESTED} with the delay duration taken from
 * {@code input.delay_ms}, defaulting to 5000 ms if not provided.
 */
@Component
public class DelayStepExecutor implements StepExecutor {

    private static final String STEP_TYPE = "DELAY";
    private static final long DEFAULT_DELAY_MS = 5_000L;

    @Override
    public String getStepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        long delayMs = DEFAULT_DELAY_MS;

        if (command.input() != null) {
            Object raw = command.input().get("delay_ms");
            if (raw instanceof Number n) {
                delayMs = n.longValue();
            } else if (raw instanceof String s) {
                delayMs = Long.parseLong(s);
            }
        }

        return StepResult.delayRequested(command.stepExecutionId(), command.attempt(), delayMs);
    }
}
