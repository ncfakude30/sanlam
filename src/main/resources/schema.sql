-- Runs on H2 for local/tests, ports to PostgreSQL. Differences noted inline.

CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT PRIMARY KEY,
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    currency CHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS withdrawals (
    id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    -- UNIQUE makes the DB the source of truth for idempotency, even under concurrency.
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    -- Reserved for dead-lettering (see docs/DECISIONS.md); not yet populated.
    dead_lettered_at TIMESTAMP NULL
);

-- Drives the "oldest unpublished first" drain. In Postgres this would be a partial index
-- (WHERE published_at IS NULL AND dead_lettered_at IS NULL); H2 has no partial indexes, so
-- we index (published_at, created_at) instead.
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (published_at, created_at);
