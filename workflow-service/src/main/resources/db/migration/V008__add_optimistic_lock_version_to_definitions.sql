ALTER TABLE workflow_definitions ADD COLUMN optimistic_lock_version BIGINT NOT NULL DEFAULT 0;
