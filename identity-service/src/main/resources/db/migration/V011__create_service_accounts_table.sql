CREATE TABLE identity.service_accounts (
    service_account_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES identity.tenants(tenant_id),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_account_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_service_accounts_tenant ON identity.service_accounts (tenant_id);
