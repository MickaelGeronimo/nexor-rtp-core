# ADR-008: Optimistic Locking vs Pessimistic Locking for Ledger Accounts

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

`LedgerAccount` balance is a highly-contested resource in concurrent payment processing. Two concurrent payment requests for the same debtor account must not result in a lost update (one update silently overwrites the other).

We need to choose between:
- **Optimistic locking** (`@Version` + `OptimisticLockException` on conflict)
- **Pessimistic locking** (`SELECT FOR UPDATE` / `PESSIMISTIC_WRITE`)
- **Application-level synchronization** (`synchronized` block)

## Decision

Use **optimistic locking** (`@Version` on `LedgerAccountJpaEntity` and `PaymentInstructionJpaEntity`) as the primary concurrency control mechanism, with the domain-level `synchronized` block in `LedgerAccount.applyLeg()` as a first-tier guard.

## Analysis

### Layer 1: Domain — `synchronized` in `applyLeg()`

Already present. Prevents lost updates **within a single JVM**. Sufficient when:
- Single instance deployment
- Balance check and application are atomic within one thread

**Gap:** Does NOT prevent lost updates across multiple JVM instances (multi-instance deployment).

### Layer 2: Database — `@Version` / Optimistic Locking

Adds a `version` column to both `ledger_accounts` and `payment_instructions`. JPA increments the version on each UPDATE. If two transactions read the same version and both attempt an UPDATE, the second will throw `OptimisticLockException` (Hibernate wraps as `StaleObjectStateException`).

**Behavior on conflict:**
- `OptimisticLockException` propagates out of the `@Transactional` boundary
- Spring rolls back the entire Unit of Work atomically
- The caller (saga) receives the exception and can retry (not yet implemented — documented as a gap)
- The idempotency layer prevents the retry from double-processing

### Why Not Pessimistic Locking?

| | Optimistic | Pessimistic |
|---|---|---|
| Lock held duration | Zero (checked at commit only) | For entire transaction duration |
| Suitable for | High-read, occasional-write | High-contention, short transactions |
| Deadlock risk | None | Yes (must order lock acquisition) |
| Impact on clearing call | None (lock released before network I/O) | **Catastrophic**: lock held during multi-second clearing network call |

**Critical:** Pessimistic locking with `SELECT FOR UPDATE` on a ledger account would hold the DB row lock for the duration of the SPI/FedNow network call (potentially 2-10 seconds), blocking all other operations on that account. This is unacceptable for a high-throughput payment rail.

## Consequences

- Added `@Version Long version` to `LedgerAccountJpaEntity` ✅ (existed already)
- Added `@Version Long version` to `PaymentInstructionJpaEntity` ✅ (new in V2 migration)
- `OptimisticLockException` propagates to the controller → mapped to HTTP 409 Conflict in `GlobalExceptionHandler`
- Known gap: no automatic retry with backoff on `OptimisticLockException` (documented in `PRODUCTION_BLUEPRINT.md`)

## Alternative Considered: Database Sequence-Based Locking

Use a dedicated `account_locks` table with advisory locks. Rejected: adds schema complexity and requires careful cleanup on crash.

## Addendum (2026-09): Implementation Gap Found and Fixed

This ADR's decision was correct, but the original implementation of `saveAccount` in
`JpaLedgerRepositoryAdapter` did not actually enforce it: it re-fetched the account row
immediately before writing and copied the new balance onto that fresh copy, so Hibernate's
`@Version` check compared the row against itself at flush time rather than against the version
the caller had originally read. Two concurrent debits computed from the same starting balance
could both be persisted, with the second silently overwriting the first (a lost update) instead
of throwing `OptimisticLockException` as this ADR describes.

Fixed by having `LedgerAccount` (domain) carry the version it was read at, and having
`saveAccount` compare that against the row's current version before writing, throwing
`OptimisticLockingFailureException` on a mismatch. See `LedgerOptimisticLockingRegressionTest`
for a test that reproduces the original bug and verifies the fix.
