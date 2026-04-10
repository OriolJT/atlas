package com.atlas.workflow.chaos;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.WorkflowDefinition;
import com.atlas.workflow.domain.WorkflowExecution;
import com.atlas.workflow.repository.WorkflowExecutionRepository;
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
class IdempotencyTest {

    @Autowired
    private ChaosTestHelper helper;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    void sameIdempotencyKeyReturnsSameExecution() {
        WorkflowDefinition definition = helper.createPublishedDefinition(
                tenantId,
                "idempotency-test-" + UUID.randomUUID(),
                List.of(Map.of(
                        "name", "step-0",
                        "type", "INTERNAL_COMMAND",
                        "retry_policy", Map.of("max_attempts", 1)
                )),
                Map.of()
        );

        String idempotencyKey = "test-key-" + UUID.randomUUID();

        // First start
        WorkflowExecution first = helper.startExecution(
                tenantId, definition.getDefinitionId(), idempotencyKey, Map.of("attempt", 1));

        // Second start with same idempotency key
        WorkflowExecution second = helper.startExecution(
                tenantId, definition.getDefinitionId(), idempotencyKey, Map.of("attempt", 2));

        // Both should return the same execution ID
        assertThat(first.getExecutionId()).isEqualTo(second.getExecutionId());

        // Only one execution should exist for this idempotency key
        var existing = executionRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        assertThat(existing).isPresent();
        assertThat(existing.get().getExecutionId()).isEqualTo(first.getExecutionId());

        // Only 1 step should have been created (not 2)
        List<?> steps = helper.getAllSteps(first.getExecutionId());
        assertThat(steps).hasSize(1);
    }
}
