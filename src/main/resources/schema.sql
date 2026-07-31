-- Schema is written to run on H2 (local/tests) and to port cleanly to PostgreSQL.
-- Portability notes are called out where the two databases differ.

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
    -- UNIQUE makes the database the source of truth for idempotency: a duplicate
    -- request can never create a second withdrawal, even under concurrency.
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
    -- Reserved for max-attempts dead-lettering (see DECISIONS.md); not yet populated.
    dead_lettered_at TIMESTAMP NULL
);

-- Supports the "oldest unpublished first" drain query.
-- PostgreSQL production improvement: make this a PARTIAL index to keep it small and
-- index only rows that still need work:
--   CREATE INDEX idx_outbox_pending ON outbox_events (created_at)
--       WHERE published_at IS NULL AND dead_lettered_at IS NULL;
-- H2 does not support partial indexes, so we index (published_at, created_at) instead.
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (published_at, created_at);
