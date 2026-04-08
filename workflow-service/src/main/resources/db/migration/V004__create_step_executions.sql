CREATE TABLE step_executions (
    step_execution_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id      UUID         NOT NULL REFERENCES workflow_executions(execution_id),
    tenant_id         UUID         NOT NULL,
    step_name         VARCHAR(255) NOT NULL,
    step_index        INT          NOT NULL,
    step_type         VARCHAR(50)  NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    attempt_count     INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 1,
    timeout_ms        BIGINT,
    input_json        JSONB        NOT NULL DEFAULT '{}',
    output_json       JSONB,
    error_message     TEXT,
    next_retry_at     TIMESTAMPTZ,
    leased_at         TIMESTAMPTZ,
    leased_by         VARCHAR(255),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    state_history     JSONB        NOT NULL DEFAULT '[]',
    compensation_for  VARCHAR(255),
    is_compensation   BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_step_executions_execution ON step_executions (execution_id);
CREATE INDEX idx_step_executions_tenant    ON step_executions (tenant_id);
CREATE INDEX idx_step_executions_status    ON step_executions (tenant_id, status);
CREATE INDEX idx_step_executions_retry     ON step_executions (next_retry_at) WHERE status = 'RETRY_SCHEDULED';
