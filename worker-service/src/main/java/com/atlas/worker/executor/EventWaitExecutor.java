package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

/**
 * Executor for steps of type EVENT_WAIT.
 *
 * <p>Returns {@link StepOutcome#WAITING} immediately. The step will be resumed externally
 * when the expected event arrives.
 */
@Component
public class EventWaitExecutor implements StepExecutor {

    private static final String STEP_TYPE = "EVENT_WAIT";

    @Override
    public String getStepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        return StepResult.waiting(command.stepExecutionId(), command.attempt());
    }
}
