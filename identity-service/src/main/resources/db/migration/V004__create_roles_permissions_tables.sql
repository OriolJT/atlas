CREATE TABLE identity.permissions (
    permission_id   UUID            PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL UNIQUE,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);

CREATE TABLE identity.roles (
    role_id         UUID            PRIMARY KEY,
    tenant_id       UUID            NOT NULL REFERENCES identity.tenants(tenant_id),
    name            VARCHAR(255)    NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_roles_tenant_id ON identity.roles (tenant_id);

CREATE TABLE identity.role_permissions (
    role_id         UUID            NOT NULL REFERENCES identity.roles(role_id),
    permission_id   UUID            NOT NULL REFERENCES identity.permissions(permission_id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE identity.user_roles (
    user_id         UUID            NOT NULL REFERENCES identity.users(user_id),
    role_id         UUID            NOT NULL REFERENCES identity.roles(role_id),
    PRIMARY KEY (user_id, role_id)
);
