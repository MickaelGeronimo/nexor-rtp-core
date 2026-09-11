-- ====================================================================
-- V4: Outbox Multi-Instance Concurrency & Locking
-- Addresses: Multiple pods/instances polling the outbox concurrently.
-- Adds:
--   1. locked_by and locked_at for node-level claiming and stale lock recovery.
--   2. Updates chk_outbox_status to support 'PROCESSING' state.
--   3. Index for status and locked_at for performant polling and reaper scans.
-- ====================================================================

-- 1. Add lock metadata columns
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_by VARCHAR(64);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP WITH TIME ZONE;

-- 2. Drop old status check constraint and re-add with PROCESSING state
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_outbox_status;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'));

-- 3. Composite index for locked_at / status to support fast claiming and stale lock eviction
CREATE INDEX IF NOT EXISTS idx_outbox_status_locked ON outbox_events(status, locked_at);
