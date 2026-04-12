ALTER TABLE audit.audit_events ADD COLUMN ingested_at TIMESTAMPTZ NOT NULL DEFAULT now();
