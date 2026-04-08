CREATE TABLE workflow_definitions (
    definition_id UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    name          VARCHAR(255) NOT NULL,
    version       INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    steps_json    JSONB,
    compensations_json JSONB   DEFAULT '{}',
    trigger_type  VARCHAR(100),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT uq_definition_tenant_name_version UNIQUE (tenant_id, name, version)
);

CREATE INDEX idx_workflow_definitions_tenant_id ON workflow_definitions (tenant_id);
