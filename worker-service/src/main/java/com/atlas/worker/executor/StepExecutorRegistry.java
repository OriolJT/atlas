package com.atlas.worker.executor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that maps step types to their {@link StepExecutor} implementations.
 *
 * <p>All {@code StepExecutor} beans are injected by Spring and indexed by
 * {@link StepExecutor#getStepType()}. An unknown step type causes an immediate exception to treat
 * the Kafka message as a poison message (it will be routed to the dead-letter topic rather than
 * retried indefinitely).
 */
@Component
public class StepExecutorRegistry {

    private final Map<String, StepExecutor> executors;

    public StepExecutorRegistry(List<StepExecutor> executors) {
        this.executors = executors.stream()
                .collect(Collectors.toMap(StepExecutor::getStepType, Function.identity()));
    }

    /**
     * Returns the executor registered for the given step type.
     *
     * @param stepType the step type string (e.g. "INTERNAL_COMMAND")
     * @return the matching executor
     * @throws IllegalArgumentException if no executor is registered for the given type
     */
    public StepExecutor getExecutor(String stepType) {
        StepExecutor executor = executors.get(stepType);
        if (executor == null) {
            throw new IllegalArgumentException(
                    "No StepExecutor registered for step type: " + stepType);
        }
        return executor;
    }
}
