package com.atlas.workflow.chaos;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.ExecutionStatus;
import com.atlas.workflow.domain.StepExecution;
import com.atlas.workflow.domain.StepStatus;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CompensationChaosTest {

    @Autowired
    private ChaosTestHelper helper;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void compensationRunsInReverseOrder() {
        // 3-step definition: step-a (comp), step-b (comp), step-c (no comp)
        // max_attempts=1 so failure is immediate dead-letter
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "comp-reverse-" + UUID.randomUUID(),
                List.of(
                        Map.of("name", "step-a", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1)),
                        Map.of("name", "step-b", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1)),
                        Map.of("name", "step-c", "type", "INTERNAL_COMMAND",
                                "retry_policy", Map.of("max_attempts", 1))
                ),
                Map.of(
                        "step-a", Map.of("type", "INTERNAL_COMMAND"),
                        "step-b", Map.of("type", "INTERNAL_COMMAND")
                        // step-c has no compensation
                )
        );

        WorkflowExecution execution = helper.startExecution(tenantId, definition.getDefinitionId(), Map.of());
        UUID executionId = execution.getExecutionId();

        // Succeed step-a
        StepExecution stepA = helper.getNextPendingStep(executionId);
        assertThat(stepA.getStepName()).isEqualTo("step-a");
        helper.simulateWorkerSuccess(stepA, Map.of("a", "done"));

        // Succeed step-b
        StepExecution stepB = helper.getNextPendingStep(executionId);
        assertThat(stepB.getStepName()).isEqualTo("step-b");
        helper.simulateWorkerSuccess(stepB, Map.of("b", "done"));

        // Fail step-c permanently (max_attempts=1, so attemptCount becomes 1 == maxAttempts -> dead-letter)
        StepExecution stepC = helper.getNextPendingStep(executionId);
        assertThat(stepC.getStepName()).isEqualTo("step-c");
        helper.simulateWorkerFailure(stepC, "step-c blew up", true);

        // After failure, execution should be in COMPENSATING
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        // step-c should be DEAD_LETTERED
        stepC = helper.refreshStep(stepC.getStepExecutionId());
        assertThat(stepC.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);

        // Find compensation steps (isCompensation=true)
        List<StepExecution> compSteps = helper.getAllSteps(executionId).stream()
                .filter(StepExecution::isCompensation)
                .sorted(Comparator.comparingInt(StepExecution::getStepIndex))
                .toList();

        // Compensation engine creates them in reverse order of succeeded steps:
        // step-b completed after step-a, so compensate-step-b runs first (index 0),
        // then compensate-step-a (index 1)
        assertThat(compSteps).hasSize(2);
        assertThat(compSteps.get(0).getStepName()).isEqualTo("compensate-step-b");
        assertThat(compSteps.get(1).getStepName()).isEqualTo("compensate-step-a");

        // Simulate success for compensation steps in order
        helper.simulateWorkerSuccess(compSteps.get(0), Map.of());
        helper.simulateWorkerSuccess(compSteps.get(1), Map.of());

        // Execution should be COMPENSATED
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATED);

        // Verify compensation step ordering: compensate-step-b completed before compensate-step-a
        StepExecution compB = helper.refreshStep(compSteps.get(0).getStepExecutionId());
        StepExecution compA = helper.refreshStep(compSteps.get(1).getStepExecutionId());
        assertThat(compB.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(compA.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(compB.getCompletedAt()).isBeforeOrEqualTo(compA.getCompletedAt());
    }

    @Test
    void fullOrderFulfillmentSagaWithFailure() {
        // Register the order-fulfillment workflow programmatically using the same structure
        // as examples/workflows/order-fulfillment.json
        List<Map<String, Object>> steps = List.of(
                Map.of("name", "validate-order", "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 3)),
                Map.of("name", "reserve-inventory", "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 3)),
                Map.of("name", "charge-payment", "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 3)),
                Map.of("name", "create-shipment", "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 1))
        );

        // Compensations: reserve-inventory and charge-payment have compensations.
        // create-shipment will fail permanently and has no compensation defined here
        // (we intentionally exclude it to test that compensation only runs for succeeded steps
        //  that have compensation definitions).
        Map<String, Object> compensations = new LinkedHashMap<>();
        compensations.put("reserve-inventory", Map.of("type", "INTERNAL_COMMAND"));
        compensations.put("charge-payment", Map.of("type", "INTERNAL_COMMAND"));

        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "order-fulfillment-" + UUID.randomUUID(),
                steps,
                compensations
        );

        WorkflowExecution execution = helper.startExecution(
                tenantId, definition.getDefinitionId(),
                Map.of("order_id", "ORD-123", "customer_id", "CUST-456")
        );
        UUID executionId = execution.getExecutionId();

        // validate-order succeeds
        StepExecution validateOrder = helper.getNextPendingStep(executionId);
        assertThat(validateOrder.getStepName()).isEqualTo("validate-order");
        helper.simulateWorkerSuccess(validateOrder, Map.of("validated", true));

        // reserve-inventory succeeds
        StepExecution reserveInventory = helper.getNextPendingStep(executionId);
        assertThat(reserveInventory.getStepName()).isEqualTo("reserve-inventory");
        helper.simulateWorkerSuccess(reserveInventory, Map.of("reservation_id", "RES-789"));

        // charge-payment succeeds
        StepExecution chargePayment = helper.getNextPendingStep(executionId);
        assertThat(chargePayment.getStepName()).isEqualTo("charge-payment");
        helper.simulateWorkerSuccess(chargePayment, Map.of("payment_id", "PAY-101"));

        // create-shipment fails permanently (max_attempts=1)
        StepExecution createShipment = helper.getNextPendingStep(executionId);
        assertThat(createShipment.getStepName()).isEqualTo("create-shipment");
        helper.simulateWorkerFailure(createShipment, "Shipment provider unavailable", true);

        // Execution should be COMPENSATING
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        // create-shipment should be dead-lettered
        createShipment = helper.refreshStep(createShipment.getStepExecutionId());
        assertThat(createShipment.getStatus()).isEqualTo(StepStatus.DEAD_LETTERED);

        // Find compensation steps
        List<StepExecution> compSteps = helper.getAllSteps(executionId).stream()
                .filter(StepExecution::isCompensation)
                .sorted(Comparator.comparingInt(StepExecution::getStepIndex))
                .toList();

        // Compensation should be in reverse order:
        // charge-payment succeeded last (among compensatable) -> compensate-charge-payment first
        // reserve-inventory succeeded before that -> compensate-reserve-inventory second
        // validate-order has no compensation defined
        assertThat(compSteps).hasSize(2);
        assertThat(compSteps.get(0).getStepName()).isEqualTo("compensate-charge-payment");
        assertThat(compSteps.get(1).getStepName()).isEqualTo("compensate-reserve-inventory");

        // Simulate compensation success: refund-payment then release-inventory
        helper.simulateWorkerSuccess(compSteps.get(0), Map.of("refunded", true));
        helper.simulateWorkerSuccess(compSteps.get(1), Map.of("released", true));

        // Execution should be COMPENSATED
        execution = helper.refreshExecution(executionId);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPENSATED);

        // Verify the forward steps that had compensation are now COMPENSATED
        reserveInventory = helper.refreshStep(reserveInventory.getStepExecutionId());
        chargePayment = helper.refreshStep(chargePayment.getStepExecutionId());
        assertThat(reserveInventory.getStatus()).isEqualTo(StepStatus.COMPENSATED);
        assertThat(chargePayment.getStatus()).isEqualTo(StepStatus.COMPENSATED);

        // validate-order should still be SUCCEEDED (no compensation defined)
        validateOrder = helper.refreshStep(validateOrder.getStepExecutionId());
        assertThat(validateOrder.getStatus()).isEqualTo(StepStatus.SUCCEEDED);

        // Total steps: 4 forward + 2 compensation = 6
        List<StepExecution> allSteps = helper.getAllSteps(executionId);
        assertThat(allSteps).hasSize(6);
    }
}
