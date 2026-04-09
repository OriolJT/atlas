UPDATE identity.tenants
SET max_workflow_definitions = 500,
    max_executions_per_minute = 300,
    max_concurrent_executions = 50,
    max_api_requests_per_minute = 3000
WHERE tenant_id = 'a0000000-0000-0000-0000-000000000010';
