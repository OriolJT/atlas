CREATE TABLE identity.api_keys (
    api_key_id UUID PRIMARY KEY,
    service_account_id UUID NOT NULL REFERENCES identity.service_accounts(service_account_id),
    tenant_id UUID NOT NULL REFERENCES identity.tenants(tenant_id),
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(8) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_hash ON identity.api_keys (key_hash);
CREATE INDEX idx_api_keys_service_account ON identity.api_keys (service_account_id);
