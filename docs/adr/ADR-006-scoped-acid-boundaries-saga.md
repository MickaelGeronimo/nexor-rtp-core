# ADR-006: Scoped ACID Boundaries in Saga Orchestration

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

A frequent anti-pattern in payment systems is wrapping an entire payment flow—from database debit, through external Central Bank HTTP/XML calls, to database settlement—inside a single `@Transactional` Spring annotation.

Under high concurrency (e.g., 2,000+ requests/second):
1. HikariCP connection pool exhaustion occurs within seconds because database connections are held idle while waiting for external network I/O (which can take 1.5–3.0 seconds).
2. Row-level database locks on debtor accounts and transit tables are held open for seconds, completely freezing unrelated concurrent operations.
3. If an external call times out, the database connection is wasted.

## Decision

Decompose the payment lifecycle into **three discrete, scoped ACID units of work** coordinated by `PaymentSagaOrchestrator`:

1. **Phase 1: Local Accounting Reservation (< 5ms)**:
   - Within `@Transactional` Unit of Work 1 (`executeAtomicReservation`):
     - Acquire idempotency lock.
     - Validate debtor balance.
     - Apply ledger hold: Debit debtor account, credit sharded transit account.
     - Persist `JournalEntry` and `PaymentInstruction` (`FUNDS_RESERVED`).
     - Insert `OutboxEvent`.
   - Commit transaction and immediately return database connection to HikariCP.

2. **Phase 2: External Rail Dispatch (100% Network, 0% Database)**:
   - Dispatch ISO 20022 `pacs.008` over mTLS via `ClearingRailPort.dispatchPayment`.
   - Executed entirely outside any database transaction. Zero database connections held open.

3. **Phase 3: Final Settlement or Compensation (< 5ms)**:
   - Within `@Transactional` Unit of Work 2:
     - If `ACSC`: Transition instruction to `SETTLED`, publish settlement event.
     - If `RJCT`: Execute compensating ledger entry (Debit transit, credit debtor), transition to `COMPENSATED`.

## Consequences

- Database connections are held for only 2–5 milliseconds instead of multiple seconds.
- Throughput scalability increases by orders of magnitude under identical database hardware specs.
- Clear separation between relational integrity and network resiliency.
