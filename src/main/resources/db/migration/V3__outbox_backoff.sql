-- ====================================================================
-- V3: Outbox Exponential Backoff
-- Addresses: retries firing on every 2s poll cycle regardless of how
-- recently they last failed, hammering a degraded Kafka broker instead
-- of backing off from it.
-- ====================================================================

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP WITH TIME ZONE;

-- Existing pending/failed rows are immediately eligible (NULL = "no backoff applied yet").
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry ON outbox_events(status, next_retry_at);
