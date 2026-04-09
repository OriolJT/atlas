package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executes steps of type INTERNAL_COMMAND by delegating to registered {@link CommandHandler} beans.
 *
 * <p>Supports failure injection via a {@code failure_config} entry in the step input, useful for
 * chaos/demo scenarios:
 * <pre>
 * failure_config:
 *   fail_at_step: "myStep"
 *   failure_type: "TRANSIENT" | "PERMANENT"
 *   fail_after_attempts: 3   // only for TRANSIENT; step succeeds once attempt >= this value
 * </pre>
 */
@Component
public class InternalCommandExecutor implements StepExecutor {

    private static final String STEP_TYPE = "INTERNAL_COMMAND";

    private final Map<String, CommandHandler> handlers;

    public InternalCommandExecutor(Map<String, CommandHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public String getStepType() {
        return STEP_TYPE;
    }

    @Override
    public StepResult execute(StepCommand command) {
        // Check failure injection before anything else
        StepResult injectedFailure = checkFailureInjection(command);
        if (injectedFailure != null) {
            return injectedFailure;
        }

        CommandHandler handler = handlers.get(command.stepName());
        if (handler == null) {
            // Pass-through: no registered handler, return input as output
            return StepResult.success(command.stepExecutionId(), command.attempt(),
                    command.input() != null ? command.input() : Map.of());
        }

        Map<String, Object> output = handler.handle(command.stepName(), command.input());
        return StepResult.success(command.stepExecutionId(), command.attempt(), output);
    }

    @SuppressWarnings("unchecked")
    private StepResult checkFailureInjection(StepCommand command) {
        if (command.input() == null) {
            return null;
        }

        Object rawConfig = command.input().get("failure_config");
        if (!(rawConfig instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> failureConfig = (Map<String, Object>) rawMap;

        String failAtStep = (String) failureConfig.get("fail_at_step");
        if (failAtStep == null || !failAtStep.equals(command.stepName())) {
            return null;
        }

        String failureType = (String) failureConfig.getOrDefault("failure_type", "TRANSIENT");

        if ("PERMANENT".equalsIgnoreCase(failureType)) {
            return StepResult.failure(command.stepExecutionId(), command.attempt(),
                    "Permanent failure injected for step: " + command.stepName(), true);
        }

        // TRANSIENT: fails until attempt >= fail_after_attempts
        int failAfterAttempts = toInt(failureConfig.getOrDefault("fail_after_attempts", 3));
        if (command.attempt() < failAfterAttempts) {
            return StepResult.failure(command.stepExecutionId(), command.attempt(),
                    "Transient failure injected for step: " + command.stepName(), false);
        }

        return null; // threshold reached — let the step proceed normally
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
