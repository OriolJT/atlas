CREATE TABLE identity.outbox (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    UUID            NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    topic           VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    tenant_id       UUID            NOT NULL,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON identity.outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON identity.outbox (published_at) WHERE published_at IS NOT NULL;
