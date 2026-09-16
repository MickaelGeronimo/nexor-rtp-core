# Architecture Decision Records (ADRs)

This directory contains the Architecture Decision Records for **Nexor RTP Core**, detailing the rationale, trade-offs, and invariants behind key architectural decisions.

| ADR | Title | Status | Scope |
| :--- | :--- | :--- | :--- |
| [ADR-001](ADR-001-hexagonal-architecture.md) | Hexagonal Architecture (Ports & Adapters) | Accepted | Domain & Application Isolation |
| [ADR-002](ADR-002-double-entry-ledger-invariants.md) | Double-Entry Ledger and Immutable Journal Entries | Accepted | Financial Ledger Core |
| [ADR-003](ADR-003-distributed-idempotency-sha256.md) | Distributed Idempotency with SHA-256 Payload Fingerprinting | Accepted | API & Concurrency Protection |
| [ADR-004](ADR-004-transactional-outbox-skip-locked.md) | Transactional Outbox Pattern with `SKIP LOCKED` | Accepted | Event Streaming (Kafka) |
| [ADR-005](ADR-005-timeout-is-not-failure.md) | Timeout is Not a Failure (Asynchronous Reconciliation) | Accepted | Clearing Rail Resiliency |
| [ADR-006](ADR-006-scoped-acid-boundaries-saga.md) | Scoped ACID Boundaries in Saga Orchestration | Accepted | High-Throughput Database Sizing |
| [ADR-007](ADR-007-rate-limiting-strategy.md) | Rate Limiting Strategy | Accepted | Edge Defense & Memory Protection |
| [ADR-008](ADR-008-optimistic-vs-pessimistic-locking.md) | Optimistic Locking vs Pessimistic Locking for Ledger Accounts | Accepted | Concurrency Invariants |
| [ADR-009](ADR-009-security-model.md) | Security Model — API Key Authentication + RBAC | Accepted | Application Security |
| [ADR-010](ADR-010-sharded-transit-buckets.md) | Sharded Transit Buckets to Eliminate Central Clearing Lock Contention | Accepted | High-Throughput Clearing Scaling |
