package com.atlas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.atlas.workflow.statemachine.StepStateMachine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "step_executions")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class StepExecution {

    @Id
    @Column(name = "step_execution_id", updatable = false, nullable = false)
    private UUID stepExecutionId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "step_name", nullable = false, updatable = false)
    private String stepName;

    @Column(name = "step_index", nullable = false, updatable = false)
    private int stepIndex;

    @Column(name = "step_type", nullable = false, updatable = false)
    private String stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StepStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> inputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private Map<String, Object> outputJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "leased_at")
    private Instant leasedAt;

    @Column(name = "leased_by")
    private String leasedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_history", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> stateHistory;

    @Column(name = "compensation_for")
    private String compensationFor;

    @Column(name = "is_compensation", nullable = false)
    private boolean isCompensation;

    protected StepExecution() {
        // JPA
    }

    private StepExecution(UUID executionId, UUID tenantId, String stepName, int stepIndex,
                          String stepType, int maxAttempts, Long timeoutMs,
                          Map<String, Object> inputJson, String compensationFor,
                          boolean isCompensation) {
        this.stepExecutionId = UUID.randomUUID();
        this.executionId = executionId;
        this.tenantId = tenantId;
        this.stepName = stepName;
        this.stepIndex = stepIndex;
        this.stepType = stepType;
        this.status = StepStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.timeoutMs = timeoutMs;
        this.inputJson = inputJson != null ? inputJson : Map.of();
        this.stateHistory = new ArrayList<>();
        this.compensationFor = compensationFor;
        this.isCompensation = isCompensation;
        appendHistory(StepStatus.PENDING, "created");
    }

    public static StepExecution create(UUID executionId, UUID tenantId, String stepName,
                                       int stepIndex, String stepType, int maxAttempts,
                                       Long timeoutMs, Map<String, Object> inputJson) {
        return new StepExecution(executionId, tenantId, stepName, stepIndex, stepType,
                maxAttempts, timeoutMs, inputJson, null, false);
    }

    public static StepExecution createCompensation(UUID executionId, UUID tenantId,
                                                    String stepName, int stepIndex,
                                                    String stepType, Map<String, Object> inputJson,
                                                    String compensationFor) {
        return new StepExecution(executionId, tenantId, stepName, stepIndex, stepType,
                1, null, inputJson, compensationFor, true);
    }

    public void transitionTo(StepStatus newStatus) {
        StepStatus previous = this.status;
        StepStateMachine.validate(previous, newStatus);
        this.status = newStatus;
        appendHistory(newStatus, "transition from " + previous);

        if (newStatus == StepStatus.RUNNING) {
            if (this.startedAt == null) {
                this.startedAt = Instant.now();
            }
            this.attemptCount++;
        } else if (newStatus == StepStatus.SUCCEEDED || newStatus == StepStatus.FAILED
                || newStatus == StepStatus.DEAD_LETTERED || newStatus == StepStatus.COMPENSATED
                || newStatus == StepStatus.COMPENSATION_FAILED) {
            this.completedAt = Instant.now();
        } else if (newStatus == StepStatus.PENDING) {
            this.leasedAt = null;
            this.leasedBy = null;
        }
    }

    public void lease(String workerId) {
        this.leasedAt = Instant.now();
        this.leasedBy = workerId;
        transitionTo(StepStatus.LEASED);
    }

    public void scheduleRetry(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
        transitionTo(StepStatus.RETRY_SCHEDULED);
    }

    private void appendHistory(StepStatus toStatus, String reason) {
        if (this.stateHistory == null) {
            this.stateHistory = new ArrayList<>();
        }
        Map<String, Object> entry = Map.of(
                "status", toStatus.name(),
                "at", Instant.now().toString(),
                "reason", reason
        );
        this.stateHistory.add(entry);
    }

    public UUID getStepExecutionId() {
        return stepExecutionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStepName() {
        return stepName;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getStepType() {
        return stepType;
    }

    public StepStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public Map<String, Object> getInputJson() {
        return inputJson;
    }

    public Map<String, Object> getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(Map<String, Object> outputJson) {
        this.outputJson = outputJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getLeasedAt() {
        return leasedAt;
    }

    public String getLeasedBy() {
        return leasedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<Map<String, Object>> getStateHistory() {
        return stateHistory;
    }

    public String getCompensationFor() {
        return compensationFor;
    }

    public boolean isCompensation() {
        return isCompensation;
    }
}
