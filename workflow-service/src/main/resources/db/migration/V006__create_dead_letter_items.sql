CREATE TABLE workflow.dead_letter_items (
    dead_letter_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    execution_id      UUID NOT NULL,
    step_execution_id UUID NOT NULL,
    step_name         VARCHAR(255) NOT NULL,
    error_message     TEXT,
    attempt_count     INT NOT NULL,
    payload           JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at       TIMESTAMPTZ,
    replayed          BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_dead_letter_tenant ON workflow.dead_letter_items (tenant_id);
CREATE INDEX idx_dead_letter_execution ON workflow.dead_letter_items (execution_id);
