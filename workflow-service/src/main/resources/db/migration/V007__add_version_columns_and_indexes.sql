-- Add optimistic locking version columns
ALTER TABLE workflow_executions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE step_executions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Add partial indexes for scheduler queries on RUNNING/LEASED steps
CREATE INDEX idx_step_executions_running_leased
    ON step_executions (status)
    WHERE status IN ('RUNNING', 'LEASED');
