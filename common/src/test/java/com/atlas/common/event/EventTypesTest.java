package com.atlas.common.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventTypesTest {

    // Identity events
    @Test void tenantCreated()          { assertEquals("tenant.created",                    EventTypes.TENANT_CREATED); }
    @Test void userCreated()            { assertEquals("user.created",                      EventTypes.USER_CREATED); }
    @Test void userRoleAssigned()       { assertEquals("user.role_assigned",                EventTypes.USER_ROLE_ASSIGNED); }
    @Test void rolePermissionsChanged() { assertEquals("role.permissions_changed",          EventTypes.ROLE_PERMISSIONS_CHANGED); }
    @Test void tokenRevoked()           { assertEquals("token.revoked",                     EventTypes.TOKEN_REVOKED); }

    // Workflow events
    @Test void workflowDefinitionPublished()  { assertEquals("workflow.definition.published",    EventTypes.WORKFLOW_DEFINITION_PUBLISHED); }
    @Test void workflowExecutionStarted()     { assertEquals("workflow.execution.started",       EventTypes.WORKFLOW_EXECUTION_STARTED); }
    @Test void workflowExecutionCompleted()   { assertEquals("workflow.execution.completed",     EventTypes.WORKFLOW_EXECUTION_COMPLETED); }
    @Test void workflowExecutionFailed()      { assertEquals("workflow.execution.failed",        EventTypes.WORKFLOW_EXECUTION_FAILED); }
    @Test void workflowExecutionCanceled()    { assertEquals("workflow.execution.canceled",      EventTypes.WORKFLOW_EXECUTION_CANCELED); }
    @Test void workflowExecutionCompensating(){ assertEquals("workflow.execution.compensating",  EventTypes.WORKFLOW_EXECUTION_COMPENSATING); }
    @Test void workflowExecutionCompensated() { assertEquals("workflow.execution.compensated",   EventTypes.WORKFLOW_EXECUTION_COMPENSATED); }

    // Step events
    @Test void workflowStepStarted()       { assertEquals("workflow.step.started",         EventTypes.WORKFLOW_STEP_STARTED); }
    @Test void workflowStepSucceeded()     { assertEquals("workflow.step.succeeded",       EventTypes.WORKFLOW_STEP_SUCCEEDED); }
    @Test void workflowStepFailed()        { assertEquals("workflow.step.failed",          EventTypes.WORKFLOW_STEP_FAILED); }
    @Test void workflowStepRetryScheduled(){ assertEquals("workflow.step.retry_scheduled", EventTypes.WORKFLOW_STEP_RETRY_SCHEDULED); }
    @Test void workflowStepDeadLettered()  { assertEquals("workflow.step.dead_lettered",   EventTypes.WORKFLOW_STEP_DEAD_LETTERED); }

    // Kafka topics
    @Test void topicStepExecute() { assertEquals("workflow.steps.execute", EventTypes.TOPIC_STEP_EXECUTE); }
    @Test void topicStepResult()  { assertEquals("workflow.steps.result",  EventTypes.TOPIC_STEP_RESULT); }
    @Test void topicAuditEvents() { assertEquals("audit.events",           EventTypes.TOPIC_AUDIT_EVENTS); }
    @Test void topicDomainEvents(){ assertEquals("domain.events",          EventTypes.TOPIC_DOMAIN_EVENTS); }
}
