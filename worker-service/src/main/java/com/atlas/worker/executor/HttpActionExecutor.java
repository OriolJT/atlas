package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stub executor for steps of type HTTP_ACTION.
 *
 * <p>In a future iteration this will use a {@code RestClient} with a per-step timeout configured
 * from {@code command.timeoutMs()}.
 */
@Component
public class HttpActionExecutor implements StepExecutor {

    private static final String STEP_TYPE = "HTTP_ACTION";

    @Override
    public String getStepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        // TODO: implement real HTTP call using RestClient with per-step timeout
        return StepResult.success(command.stepExecutionId(), command.attempt(),
                Map.of("note", "HTTP_ACTION stub — real HTTP call not yet implemented"));
    }
}
