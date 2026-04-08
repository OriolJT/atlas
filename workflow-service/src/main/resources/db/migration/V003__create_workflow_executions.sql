CREATE TABLE workflow_executions (
    execution_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    definition_id    UUID         NOT NULL REFERENCES workflow_definitions(definition_id),
    idempotency_key  VARCHAR(255) NOT NULL,
    status           VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    input_json       JSONB        NOT NULL DEFAULT '{}',
    output_json      JSONB,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    canceled_at      TIMESTAMPTZ,
    timed_out_at     TIMESTAMPTZ,
    correlation_id   VARCHAR(255),
    CONSTRAINT uq_execution_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_workflow_executions_tenant     ON workflow_executions (tenant_id);
CREATE INDEX idx_workflow_executions_status     ON workflow_executions (tenant_id, status);
CREATE INDEX idx_workflow_executions_definition ON workflow_executions (definition_id);
