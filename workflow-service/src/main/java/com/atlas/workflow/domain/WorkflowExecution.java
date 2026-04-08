package com.atlas.workflow.domain;

import com.atlas.workflow.statemachine.ExecutionStateMachine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_executions")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WorkflowExecution {

    @Id
    @Column(name = "execution_id", updatable = false, nullable = false)
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private Map<String, Object> outputJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "timed_out_at")
    private Instant timedOutAt;

    @Column(name = "correlation_id")
    private String correlationId;

    protected WorkflowExecution() {
        // JPA
    }

    private WorkflowExecution(UUID tenantId, UUID definitionId, String idempotencyKey,
                               Map<String, Object> inputJson, String correlationId) {
        this.executionId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.idempotencyKey = idempotencyKey;
        this.status = ExecutionStatus.PENDING;
        this.inputJson = inputJson != null ? inputJson : Map.of();
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    public static WorkflowExecution create(UUID tenantId, UUID definitionId,
                                            String idempotencyKey,
                                            Map<String, Object> inputJson,
                                            String correlationId) {
        return new WorkflowExecution(tenantId, definitionId, idempotencyKey, inputJson, correlationId);
    }

    public void transitionTo(ExecutionStatus newStatus) {
        ExecutionStateMachine.validateTransition(this.status, newStatus);
        this.status = newStatus;

        Instant now = Instant.now();
        switch (newStatus) {
            case RUNNING    -> this.startedAt = now;
            case COMPLETED  -> this.completedAt = now;
            case CANCELED   -> this.canceledAt = now;
            case TIMED_OUT  -> this.timedOutAt = now;
            default         -> { /* no timestamp update needed */ }
        }
    }

    public void fail(String errorMessage) {
        ExecutionStateMachine.validateTransition(this.status, ExecutionStatus.FAILED);
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    // --- Getters ---

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDefinitionId() {
        return definitionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public Map<String, Object> getInputJson() {
        return inputJson;
    }

    public Map<String, Object> getOutputJson() {
        return outputJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public Instant getTimedOutAt() {
        return timedOutAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setOutputJson(Map<String, Object> outputJson) {
        this.outputJson = outputJson;
    }
}
