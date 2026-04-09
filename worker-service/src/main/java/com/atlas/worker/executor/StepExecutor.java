package com.atlas.worker.executor;

public interface StepExecutor {

    StepResult execute(StepCommand command);

    String getStepType();
}
