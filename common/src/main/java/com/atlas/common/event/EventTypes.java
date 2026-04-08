package com.atlas.common.event;

public final class EventTypes {
    private EventTypes() {}

    // Identity events
    public static final String TENANT_CREATED = "tenant.created";
    public static final String USER_CREATED = "user.created";
    public static final String USER_ROLE_ASSIGNED = "user.role_assigned";
    public static final String ROLE_PERMISSIONS_CHANGED = "role.permissions_changed";
    public static final String TOKEN_REVOKED = "token.revoked";

    // Workflow events
    public static final String WORKFLOW_DEFINITION_PUBLISHED = "workflow.definition.published";
    public static final String WORKFLOW_EXECUTION_STARTED = "workflow.execution.started";
    public static final String WORKFLOW_EXECUTION_COMPLETED = "workflow.execution.completed";
    public static final String WORKFLOW_EXECUTION_FAILED = "workflow.execution.failed";
    public static final String WORKFLOW_EXECUTION_CANCELED = "workflow.execution.canceled";
    public static final String WORKFLOW_EXECUTION_COMPENSATING = "workflow.execution.compensating";
    public static final String WORKFLOW_EXECUTION_COMPENSATED = "workflow.execution.compensated";

    // Step events
    public static final String WORKFLOW_STEP_STARTED = "workflow.step.started";
    public static final String WORKFLOW_STEP_SUCCEEDED = "workflow.step.succeeded";
    public static final String WORKFLOW_STEP_FAILED = "workflow.step.failed";
    public static final String WORKFLOW_STEP_RETRY_SCHEDULED = "workflow.step.retry_scheduled";
    public static final String WORKFLOW_STEP_DEAD_LETTERED = "workflow.step.dead_lettered";

    // Kafka topics
    public static final String TOPIC_STEP_EXECUTE = "workflow.steps.execute";
    public static final String TOPIC_STEP_RESULT = "workflow.steps.result";
    public static final String TOPIC_AUDIT_EVENTS = "audit.events";
    public static final String TOPIC_DOMAIN_EVENTS = "domain.events";
}
