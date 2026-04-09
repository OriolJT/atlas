package com.atlas.worker.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepExecutorRegistryTest {

    private StepExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StepExecutorRegistry(List.of(
                new DelayStepExecutor(),
                new EventWaitExecutor(),
                new HttpActionExecutor(),
                new InternalCommandExecutor(java.util.Map.of())
        ));
    }

    @Test
    void getExecutorForKnownType_returnsExecutor() {
        StepExecutor executor = registry.getExecutor("DELAY");

        assertThat(executor).isInstanceOf(DelayStepExecutor.class);
        assertThat(executor.getStepType()).isEqualTo("DELAY");
    }

    @Test
    void getExecutorForUnknownType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> registry.getExecutor("UNKNOWN_TYPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_TYPE");
    }
}
