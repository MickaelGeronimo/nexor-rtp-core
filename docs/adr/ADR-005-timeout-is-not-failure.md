# ADR-005: Timeout is Not a Failure (Asynchronous Reconciliation)

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

Central Bank real-time payment networks (Bacen SPI for Pix, Federal Reserve FedNow, TIPS in Europe) operate under strict SLAs (e.g., < 2.5s end-to-end).

However, internet jitter, clearing gateway latency spikes, or temporary central bank network blips can cause the client socket to time out before receiving a final `pacs.002` acknowledgment.

A naive error handling implementation treats a timeout as an error and executes an immediate rollback/refund to the debtor customer. If the Central Bank processed the order 200ms after the timeout, the funds are delivered to the recipient bank, while the debtor's funds were refunded internally. This results in **direct double-credit financial loss** for the sending financial institution.

## Decision

Treat network timeouts strictly as **indeterminate states**, transitioning the payment to `PENDING_INVESTIGATION`:

1. **No Premature Rollbacks**:
   - When a dispatch call to `ClearingRailPort` times out, never roll back debtor funds or reverse the ledger entry immediately.
   - Retain funds in the designated clearing transit buffer account (`TRANSIT-XXX`).

2. **Asynchronous Reconciliation Loop (`PaymentReconciliationWorker`)**:
   - A scheduled worker periodically polls all payments in `PENDING_INVESTIGATION`.
   - Dispatches a status inquiry message (`pacs.028`) to the Central Bank clearing rail.

3. **Authoritative State Resolution**:
   - **ACSC (Accepted Settlement Completed)**: Transition payment to `SETTLED`. The transit balance is considered cleared.
   - **RJCT (Rejected)**: Trigger saga compensating transaction, debiting transit and crediting the debtor account via a reversal journal entry. Payment transitions to `COMPENSATED`.

## Consequences

- Completely eliminates double-credit losses caused by false-negative network timeouts.
- In-flight funds remain auditable and locked in transit until authoritative confirmation is secured.
- Customers receive clear `PENDING_INVESTIGATION` feedback rather than incorrect failure alerts.
