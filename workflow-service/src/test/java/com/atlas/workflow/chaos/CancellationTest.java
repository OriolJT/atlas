package com.atlas.workflow.chaos;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.service.WorkflowExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CancellationTest {

    @Autowired
    private ChaosTestHelper helper;

    @Autowired
    private WorkflowExecutionService workflowExecutionService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void cancelStopsExecution() {
        // 3-step definition
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "cancel-test-" + UUID.randomUUID(),
                List.of(
                        Map.of("name", "step-0", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1)),
                        Map.of("name", "step-1", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1)),
                        Map.of("name", "step-2", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1))
                ),
                Map.of()
        );

        WorkflowExecution execution = helper.startExecution(tenantId, definition.getDefinitionId(), Map.of());
        UUID executionId = execution.getExecutionId();

        // Succeed step-0 -> step-1 created in PENDING
        StepExecution step0 = helper.getNextPendingStep(executionId);
        assertThat(step0.getStepName()).isEqualTo("step-0");
        helper.simulateWorkerSuccess(step0, Map.of("progress", "step-0 done"));

        // Verify step-1 exists in PENDING
        StepExecution step1 = helper.getNextPendingStep(executionId);
        assertThat(step1).isNotNull();
        assertThat(step1.getStepName()).isEqualTo("step-1");

        // Cancel the execution before step-1 is processed
        workflowExecutionService.cancel(executionId, tenantId);

        // Execution should be CANCELED
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELED);

        // Attempt to process step-1 after cancellation -- should be ignored
        helper.simulateWorkerSuccess(step1, Map.of("should", "be ignored"));

        // step-1 should NOT be SUCCEEDED (the processor ignores results for CANCELED executions)
        step1 = helper.refreshStep(step1.getStepExecutionId());
        assertThat(step1.getStatus()).isNotEqualTo(StepStatus.SUCCEEDED);

        // No step-2 should have been created
        List<StepExecution> allSteps = helper.getAllSteps(executionId);
        assertThat(allSteps).hasSize(2); // step-0 + step-1 only

        // step-0 should still be SUCCEEDED (completed before cancel)
        step0 = helper.refreshStep(step0.getStepExecutionId());
        assertThat(step0.getStatus()).isEqualTo(StepStatus.SUCCEEDED);

        // Execution should remain CANCELED
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELED);
    }
}
