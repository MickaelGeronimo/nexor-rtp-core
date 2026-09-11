-- ====================================================================
-- V1: Baseline Schema for Nexor RTP Core
-- Immutable Double-Entry Ledger, Idempotency, Payments & Transactional Outbox
-- ====================================================================

-- 1. Distributed Idempotency Table
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_keys(expires_at);

-- 2. Payment Instructions (Core Aggregate)
CREATE TABLE IF NOT EXISTS payment_instructions (
    transaction_id VARCHAR(64) PRIMARY KEY,
    end_to_end_id VARCHAR(35) UNIQUE NOT NULL,
    debtor_account VARCHAR(64) NOT NULL,
    creditor_account VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    rail VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    clearing_reference VARCHAR(64),
    failure_reason TEXT,
    remittance_info TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payments_end_to_end ON payment_instructions(end_to_end_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payment_instructions(status);

-- 3. Double-Entry General Ledger Accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    account_name VARCHAR(128) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    allow_overdraft BOOLEAN DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0
);

-- 4. Journal Entries (Immutable Accounting Transactions)
CREATE TABLE IF NOT EXISTS journal_entries (
    entry_id VARCHAR(64) PRIMARY KEY,
    reference_id VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    memo TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_journal_reference ON journal_entries(reference_id);

-- 5. Posting Legs (Debits & Credits)
CREATE TABLE IF NOT EXISTS posting_legs (
    leg_id BIGSERIAL PRIMARY KEY,
    entry_id VARCHAR(64) REFERENCES journal_entries(entry_id) ON DELETE CASCADE,
    account_id VARCHAR(64) NOT NULL REFERENCES ledger_accounts(account_id),
    posting_type VARCHAR(8) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description TEXT
);
CREATE INDEX IF NOT EXISTS idx_legs_entry ON posting_legs(entry_id);
CREATE INDEX IF NOT EXISTS idx_legs_account ON posting_legs(account_id);

-- 6. Transactional Outbox
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events(status, created_at);
