package com.atlas.workflow.chaos;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.service.StepResultProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DeduplicationTest {

    @Autowired
    private ChaosTestHelper helper;

    @Autowired
    private StepResultProcessor stepResultProcessor;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void duplicateResultIsIgnored() {
        // Create a 2-step definition so we can verify the second step is created only once
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "dedup-test-" + UUID.randomUUID(),
                List.of(
                        Map.of("name", "step-0", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1)),
                        Map.of("name", "step-1", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1))
                ),
                Map.of()
        );

        WorkflowExecution execution = helper.startExecution(tenantId, definition.getDefinitionId(), Map.of());
        UUID executionId = execution.getExecutionId();

        // Get the first pending step and simulate success
        StepExecution step0 = helper.getNextPendingStep(executionId);
        assertThat(step0).isNotNull();
        assertThat(step0.getStepName()).isEqualTo("step-0");

        helper.simulateWorkerSuccess(step0, Map.of("result", "first-call"));

        // Verify step-0 is SUCCEEDED
        step0 = helper.refreshStep(step0.getStepExecutionId());
        assertThat(step0.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step0.getOutputJson()).containsEntry("result", "first-call");

        // Count steps before duplicate
        List<StepExecution> stepsBefore = helper.getAllSteps(executionId);
        assertThat(stepsBefore).hasSize(2); // step-0 (SUCCEEDED) + step-1 (PENDING)

        // Send duplicate result for step-0 with the same attempt count
        // The processor should detect the step is in terminal status and ignore it
        Map<String, Object> duplicatePayload = new HashMap<>();
        duplicatePayload.put("step_execution_id", step0.getStepExecutionId().toString());
        duplicatePayload.put("outcome", "SUCCEEDED");
        duplicatePayload.put("attempt", step0.getAttemptCount());
        duplicatePayload.put("output", Map.of("result", "duplicate-call"));
        duplicatePayload.put("error", null);
        stepResultProcessor.process(duplicatePayload);

        // Step-0 should still have original output (not overwritten by duplicate)
        step0 = helper.refreshStep(step0.getStepExecutionId());
        assertThat(step0.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step0.getOutputJson()).containsEntry("result", "first-call");

        // Still only 2 steps (no third step created by duplicate)
        List<StepExecution> stepsAfter = helper.getAllSteps(executionId);
        assertThat(stepsAfter).hasSize(2);

        // Execution should still be RUNNING (waiting for step-1)
        WorkflowExecution updated = helper.refreshExecution(executionId);
        assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }
}
