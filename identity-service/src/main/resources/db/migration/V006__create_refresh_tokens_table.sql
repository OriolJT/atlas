CREATE TABLE identity.refresh_tokens (
    token_id    UUID PRIMARY KEY,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    user_id     UUID NOT NULL REFERENCES identity.users(user_id),
    tenant_id   UUID NOT NULL REFERENCES identity.tenants(tenant_id),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON identity.refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON identity.refresh_tokens(token_hash);
