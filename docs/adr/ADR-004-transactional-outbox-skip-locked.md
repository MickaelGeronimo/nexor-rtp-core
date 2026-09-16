# ADR-004: Transactional Outbox Pattern with SKIP LOCKED

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

Publishing domain events directly to Apache Kafka within a database transaction boundary introduces dual-write hazards:
1. If the database transaction rolls back after the Kafka message was sent, an event for a non-existent transaction has been published (phantom event).
2. If Kafka is temporarily unreachable, the database transaction fails or hangs, leading to connection exhaustion.

## Decision

Implement the **Transactional Outbox Pattern** with concurrent worker polling using PostgreSQL `FOR UPDATE SKIP LOCKED`:

1. **Atomic Local Outbox Insertion**:
   - Write domain events (`outbox_events` table) in the exact same relational transaction that persists the payment instruction and ledger journal entries.

2. **Asynchronous Polling Relay (`OutboxRelayWorker`)**:
   - Background worker claims batches of pending events (`LIMIT 50`) using:
     ```sql
     SELECT * FROM outbox_events 
     WHERE status = 'PENDING' OR (status = 'FAILED' AND retry_count < 3) 
     ORDER BY created_at ASC 
     FOR UPDATE SKIP LOCKED;
     ```
   - Multiple worker threads or pods in Kubernetes process disjoint sets of events concurrently without locking conflicts.

3. **Dead Letter Queue (DLQ) Quarantine**:
   - On Kafka dispatch failure, increment `retry_count` and back off.
   - If `retry_count >= 3`, transition event to `DEAD_LETTER` to prevent poison-pill events from blocking the queue.

## Consequences

- 100% at-least-once delivery guarantee to Kafka without two-phase commit (XA) overhead.
- Total decoupling of database availability from Kafka broker health.
- Consumers downstream must be idempotent to handle rare at-least-once duplicates.
