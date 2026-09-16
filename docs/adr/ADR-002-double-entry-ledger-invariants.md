# ADR-002: Double-Entry Ledger and Immutable Journal Entries

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

Single-entry bookkeeping (updating an account balance column in isolation) provides no audit trail, makes balance reconciliation impossible, and cannot survive discrepancies or partial systemic failures.

In banking and payment systems, regulatory standards require an immutable audit log of every monetary movement where no value is ever created or destroyed.

## Decision

Implement an immutable **Double-Entry Ledger Engine** with strict zero-sum balancing:

1. **Immutable Journal Entries (`JournalEntry`)**:
   - Once persisted, journal entries and posting legs can never be updated or deleted.
   - Each entry consists of at least two posting legs (`PostingLeg`).

2. **Zero-Sum Balance Invariant**:
   $$\sum \text{Debits} - \sum \text{Credits} = 0$$
   Any journal entry whose legs do not sum to zero is rejected before hitting the database.

3. **Account Classification**:
   - Accounts are strictly typed (`ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`).
   - Normal balance rules are enforced in domain aggregates.

4. **Continuous Integrity Audit**:
   - `LedgerIntegrityService` verifies that the balance of every `LedgerAccount` equals the exact historical sum of its posting legs.

## Consequences

- Direct balance manipulation without an accompanying journal entry is impossible in domain models.
- Auditing and regulatory compliance are guaranteed by design.
- Compensating actions (refunds, clearing rejections) must be posted as new reversing journal entries rather than mutating past entries.
