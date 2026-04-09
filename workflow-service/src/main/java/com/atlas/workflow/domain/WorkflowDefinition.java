package com.atlas.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WorkflowDefinition {

    @Id
    @Column(name = "definition_id", updatable = false, nullable = false)
    private UUID definitionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DefinitionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps_json", columnDefinition = "jsonb")
    private Object stepsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "compensations_json", columnDefinition = "jsonb")
    private Map<String, Object> compensationsJson;

    @Column(name = "trigger_type")
    private String triggerType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkflowDefinition() {
        // JPA
    }

    private WorkflowDefinition(UUID tenantId, String name, int version,
                                Object stepsJson,
                                Map<String, Object> compensationsJson,
                                String triggerType) {
        this.definitionId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.version = version;
        this.status = DefinitionStatus.DRAFT;
        this.stepsJson = stepsJson;
        this.compensationsJson = compensationsJson != null ? compensationsJson : Map.of();
        this.triggerType = triggerType;
        this.createdAt = Instant.now();
    }

    public static WorkflowDefinition create(UUID tenantId, String name, int version,
                                             Object stepsJson,
                                             Map<String, Object> compensationsJson,
                                             String triggerType) {
        return new WorkflowDefinition(tenantId, name, version, stepsJson, compensationsJson, triggerType);
    }

    public void publish() {
        if (this.status != DefinitionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot publish workflow definition in status " + this.status + "; must be DRAFT");
        }
        this.status = DefinitionStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public UUID getDefinitionId() {
        return definitionId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public DefinitionStatus getStatus() {
        return status;
    }

    public Object getStepsJson() {
        return stepsJson;
    }

    public Map<String, Object> getCompensationsJson() {
        return compensationsJson;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
