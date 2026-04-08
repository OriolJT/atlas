CREATE TABLE outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}',
    tenant_id       UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox (aggregate_type, aggregate_id);
