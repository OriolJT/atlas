package com.atlas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_items", schema = "workflow")
public class DeadLetterItem {

    @Id
    @Column(name = "dead_letter_id", updatable = false, nullable = false)
    private UUID deadLetterId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "step_execution_id", nullable = false, updatable = false)
    private UUID stepExecutionId;

    @Column(name = "step_name", nullable = false, updatable = false)
    private String stepName;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replayed", nullable = false)
    private boolean replayed;

    protected DeadLetterItem() {
        // JPA
    }

    private DeadLetterItem(UUID tenantId, UUID executionId, UUID stepExecutionId,
                           String stepName, String errorMessage, int attemptCount,
                           Map<String, Object> payload) {
        this.deadLetterId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.executionId = executionId;
        this.stepExecutionId = stepExecutionId;
        this.stepName = stepName;
        this.errorMessage = errorMessage;
        this.attemptCount = attemptCount;
        this.payload = payload != null ? payload : Map.of();
        this.createdAt = Instant.now();
        this.replayed = false;
    }

    public static DeadLetterItem create(UUID tenantId, UUID executionId, UUID stepExecutionId,
                                        String stepName, String errorMessage, int attemptCount,
                                        Map<String, Object> payload) {
        return new DeadLetterItem(tenantId, executionId, stepExecutionId,
                stepName, errorMessage, attemptCount, payload);
    }

    public void replay() {
        this.replayed = true;
        this.replayedAt = Instant.now();
    }

    // --- Getters ---

    public UUID getDeadLetterId() {
        return deadLetterId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getStepExecutionId() {
        return stepExecutionId;
    }

    public String getStepName() {
        return stepName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
