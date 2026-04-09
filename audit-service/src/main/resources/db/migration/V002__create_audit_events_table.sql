CREATE TABLE audit.audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id UUID,
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID,
    payload JSONB NOT NULL DEFAULT '{}',
    correlation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_events_tenant ON audit.audit_events (tenant_id);
CREATE INDEX idx_audit_events_tenant_occurred ON audit.audit_events (tenant_id, occurred_at);
CREATE INDEX idx_audit_events_type ON audit.audit_events (tenant_id, event_type);
CREATE INDEX idx_audit_events_resource ON audit.audit_events (tenant_id, resource_type, resource_id);
