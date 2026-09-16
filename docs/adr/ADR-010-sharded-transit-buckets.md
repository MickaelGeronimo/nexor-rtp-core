# ADR-010: Sharded Transit Buckets to Eliminate Central Clearing Lock Contention

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

In instant payment rails (Pix, FedNow), every outbound payment places held funds into an internal clearing transit account representing liquidity reserved for Central Bank settlement before dispatch.

If a single monolithic transit account (`TRANSIT-001`) is used:
1. Every concurrent thread executing Phase 1 (accounting reservation) must update the exact same account row in `ledger_accounts`.
2. Whether using optimistic locking (`@Version`) or pessimistic locking (`FOR UPDATE`), 5,000 concurrent payments per second attempt to mutate that single row simultaneously.
3. Under optimistic locking, 99% of transactions fail with `OptimisticLockingFailureException` and require retries. Under pessimistic locking, database threads serialize into a massive queue, HikariCP runs out of connections, and transaction timeouts skyrocket.

## Decision

Partition the clearing settlement transit liability into **16 Sharded Transit Buckets** (`TRANSIT-001` through `TRANSIT-016`):

1. **Deterministic Modulo Sharding**:
   - For every payment transaction, compute its target transit bucket using the deterministic hash of its `TransactionId`:
     $$\text{bucketIndex} = \left(\left|\text{txId.hashCode()}\right| \pmod{16}\right) + 1$$
     $$\text{transitId} = \text{AccountId.of}(\text{String.format}("\text{TRANSIT}-\%03\text{d}", \text{bucketIndex}), "0001", "\text{CLEARING}")$$

2. **Full Lifecycle Determinism**:
   - Both the reservation leg (Phase 1) and the settlement/compensation leg (Phase 3 or Reconciliation Worker) resolve the exact same bucket using the deterministic transaction identifier.

3. **Bootstrap and Ledger Seeding**:
   - The ledger initialization routines (`InMemoryLedgerRepository`, `JpaLedgerRepositoryAdapter`) pre-seed all 16 transit accounts on startup with zero balance.

## Consequences

- Row contention on the clearing transit balance is cut by a factor of 16 immediately.
- Concurrent payments hash to independent rows in PostgreSQL, eliminating `OptimisticLockingFailureException` hotspots.
- Global clearing balance is calculated trivially by summing the balances of the 16 transit shards during reconciliation audits.
