CREATE TABLE identity.users (
    user_id              UUID            PRIMARY KEY,
    tenant_id            UUID            NOT NULL REFERENCES identity.tenants(tenant_id),
    email                VARCHAR(255)    NOT NULL,
    password_hash        VARCHAR(255)    NOT NULL,
    first_name           VARCHAR(100)    NOT NULL,
    last_name            VARCHAR(100)    NOT NULL,
    status               VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INT            NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP,
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email)
);
CREATE INDEX idx_users_tenant_id ON identity.users (tenant_id);
CREATE INDEX idx_users_email ON identity.users (email);
