package com.atlas.worker.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCommandExecutorTest {

    private InternalCommandExecutor executor;

    private StepCommand command(String stepName, int attempt, Map<String, Object> input) {
        return new StepCommand(
                "step-exec-1", "exec-1", "tenant-1",
                stepName, "INTERNAL_COMMAND", attempt,
                input, 5000L, false, null
        );
    }

    @BeforeEach
    void setUp() {
        CommandHandler echoHandler = (stepName, input) ->
                Map.of("echoed", input.getOrDefault("value", "none"));

        executor = new InternalCommandExecutor(Map.of("echoStep", echoHandler));
    }

    @Test
    void executeWithRegisteredHandler_returnsSucceededWithHandlerOutput() {
        StepCommand cmd = command("echoStep", 1, Map.of("value", "hello"));

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.output()).containsEntry("echoed", "hello");
        assertThat(result.error()).isNull();
    }

    @Test
    void executeWithNoHandler_returnsSucceededWithPassThrough() {
        Map<String, Object> input = Map.of("key", "value");
        StepCommand cmd = command("unknownStep", 1, input);

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.output()).isEqualTo(input);
    }

    @Test
    void executeWithPermanentFailureInjection_returnsFailed() {
        Map<String, Object> input = new HashMap<>();
        input.put("failure_config", Map.of(
                "fail_at_step", "echoStep",
                "failure_type", "PERMANENT"
        ));
        StepCommand cmd = command("echoStep", 5, input);

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isTrue();
        assertThat(result.error()).contains("Permanent failure injected");
    }

    @Test
    void executeWithTransientFailureBeforeThreshold_returnsFailed() {
        Map<String, Object> input = new HashMap<>();
        input.put("failure_config", Map.of(
                "fail_at_step", "echoStep",
                "failure_type", "TRANSIENT",
                "fail_after_attempts", 3
        ));
        // attempt=2 < fail_after_attempts=3 → should fail
        StepCommand cmd = command("echoStep", 2, input);

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.nonRetryable()).isFalse();
        assertThat(result.error()).contains("Transient failure injected");
    }

    @Test
    void executeWithTransientFailureAfterThreshold_returnsSucceeded() {
        Map<String, Object> input = new HashMap<>();
        input.put("failure_config", Map.of(
                "fail_at_step", "echoStep",
                "failure_type", "TRANSIENT",
                "fail_after_attempts", 3
        ));
        input.put("value", "hi");
        // attempt=3 >= fail_after_attempts=3 → should succeed
        StepCommand cmd = command("echoStep", 3, input);

        StepResult result = executor.execute(cmd);

        assertThat(result.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(result.output()).containsEntry("echoed", "hi");
    }
}
