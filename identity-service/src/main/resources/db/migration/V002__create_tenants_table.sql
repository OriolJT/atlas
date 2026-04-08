CREATE TABLE identity.tenants (
    tenant_id   UUID            PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    slug        VARCHAR(100)    NOT NULL UNIQUE,
    status      VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tenants_slug ON identity.tenants (slug);
CREATE INDEX idx_tenants_status ON identity.tenants (status);
