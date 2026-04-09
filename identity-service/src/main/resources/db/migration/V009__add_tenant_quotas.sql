ALTER TABLE identity.tenants
    ADD COLUMN max_workflow_definitions INT NOT NULL DEFAULT 100,
    ADD COLUMN max_executions_per_minute INT NOT NULL DEFAULT 60,
    ADD COLUMN max_concurrent_executions INT NOT NULL DEFAULT 10,
    ADD COLUMN max_api_requests_per_minute INT NOT NULL DEFAULT 600;
