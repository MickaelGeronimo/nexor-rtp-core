# ADR-003: Distributed Idempotency with SHA-256 Payload Fingerprinting

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

In real-time payment networks, client network retries and webhook retries are standard occurrences. If a client sends a payment request, experiences an HTTP connection drop, and retries the request with the same `Idempotency-Key`, the system must not process a duplicate debit.

Additionally, an attacker or buggy client could attempt to reuse a previously successful `Idempotency-Key` with altered payment parameters (e.g., higher amount or different recipient).

## Decision

Implement database-backed idempotency with **deterministic SHA-256 payload fingerprinting**:

1. **Fingerprint Calculation**:
   - Compute a deterministic SHA-256 digest over normalized request fields:
     $$\text{Fingerprint} = \text{SHA-256}(\text{debtor} \parallel \text{creditor} \parallel \text{amount} \parallel \text{currency} \parallel \text{remittance})$$

2. **Atomicity and Locking**:
   - Persist an `IdempotencyRecord` in PostgreSQL with a unique constraint on `idempotency_key`.
   - Utilize lease acquisition with a 2-minute expiration window to detect in-flight duplicate requests concurrently submitted across cluster nodes.

3. **Collision / Tampering Handling**:
   - **Identical Fingerprint & Completed Status**: Return original stored response immediately (`HTTP 200/201`) without re-executing business logic.
   - **Divergent Fingerprint**: Reject immediately with `HTTP 409 Conflict` (payload tampering / key collision).
   - **In-flight Duplicate**: Reject with `HTTP 409 Conflict` to prevent concurrent race conditions.

## Consequences

- Zero double-spending risk across distributed application instances.
- Detects fraudulent or accidental payload tampering on reused keys.
- Cache invalidation and lease timeouts are governed by strict timestamps.
