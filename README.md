# Nexor RTP Core

High-throughput reference implementation for ISO 20022 real-time payment processing (Pix / SPI, FedNow, RTP) built with Java 17, Spring Boot, PostgreSQL, and Apache Kafka.

[![Java 17](https://img.shields.io/badge/Java-17%20LTS-orange.svg)](https://openjdk.org/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Apache Kafka](https://img.shields.io/badge/Kafka-KRaft-black.svg)](https://kafka.apache.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

---

## System Overview

In real-time payment systems (instant clearing rails like Bacen SPI and FedNow), payments must clear within strict regulatory windows (typically under 2.5 seconds) while guaranteeing that no money is duplicated or lost during network partitions, database failovers, or node crashes.

Nexor RTP Core implements an ISO 20022 payment orchestration switch structured around Hexagonal Architecture (Ports and Adapters). It isolates external network latency from database connection pools, prevents double-debit anomalies via distributed idempotency, and maintains auditable double-entry ledger state across all lifecycle phases.

```
                   ┌─────────────────────────────────────────────────────────┐
                   │                 Payment Pipeline                        │
                   └─────────────────────────────────────────────────────────┘
    Idempotency           Fraud/AML           Smart Rail        Double-Entry Hold        ISO 20022 Clearing         Settlement /
    Lock & Fingerprint    Rule Chain          Router            (Posting Legs)           (Bacen SPI / FedNow)       Auto-Compensate
   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────────────┐    ┌────────────────────┐    ┌─────────────────┐
   │  SHA-256     │───▶│  Sanctions   │───▶│  Auto-route  │───▶│ Debit Debtor      │───▶│ pacs.008 Dispatch  │───▶│ ACSC: Settled   │
   │  Distributed │    │  Velocity    │    │  Pix/FedNow/ │    │ Credit Transit    │    │ pacs.002 Response  │    │ RJCT: Reverse   │
   │  DB Lock     │    │  High-Value  │    │  BookTransfer│    │ ACID Unit of Work │    │ Timeout Resilient  │    │ Hold in Ledger  │
   └──────────────┘    └──────────────┘    └──────────────┘    └───────────────────┘    └────────────────────┘    └─────────────────┘
```

---

## Core Engineering Decisions

### 1. Scoped ACID Units of Work (Connection Pool Preservation)
Wrapping an end-to-end payment flow inside a single `@Transactional` method is a critical vulnerability: any slow downstream call to a clearing rail or central bank network holds a database connection open. Under latency spikes, HikariCP connection pools exhaust in seconds, causing cascading 500 errors across the entire platform.

Nexor divides the lifecycle into discrete, local ACID transactions:
- **Phase 1 (Reservation):** In a short transaction (< 5ms), the engine validates the request, locks the idempotency record, and posts an immutable ledger hold (Debit Debtor, Credit Transit).
- **Phase 2 (Clearing Dispatch):** Network I/O (dispatching `pacs.008` to the clearing rail) executes strictly outside any database transaction.
- **Phase 3 (Settlement or Reversal):** Upon receiving the clearing response, a secondary short transaction commits final settlement or executes compensating reversal legs.

### 2. Distributed Idempotency Engine
- Every request computes a deterministic **SHA-256 fingerprint** over payment parameters (`debtor|creditor|amount|currency|remittance`).
- Transitions state atomically in `idempotency_keys` (`IN_FLIGHT` with lease $\to$ `COMPLETED`).
- Reusing an existing key with altered payment attributes immediately aborts with `409 Conflict` (`ConflictingPayloadException`).
- Unfinished in-flight leases are reclaimed after expiry if an executing node crashes mid-flight.

### 3. Asynchronous Clearing Timeout Handling (No Premature Chargebacks)
When a clearing rail times out without returning a `pacs.002`, treating the operation as a failure and immediately refunding the customer causes severe double-credit risk (if the central bank actually settled the transaction seconds later).
- In Nexor, unconfirmed timeouts transition the payment to `PENDING_INVESTIGATION`.
- Funds remain held in the transit account without premature release.
- A background reconciliation worker queries the rail asynchronously before committing final settlement or triggering saga compensation.

### 4. Transactional Outbox with Multi-Instance Claiming
- Eliminates dual-write bugs between PostgreSQL and Apache Kafka by persisting domain events into `outbox_events` within the reservation transaction.
- The `OutboxRelayWorker` polls pending events using `SELECT ... FOR UPDATE SKIP LOCKED`, allowing horizontally scaled pods to claim distinct batches without row contention or lock serialization.
- Failed deliveries use exponential backoff with jitter and are quarantined to `DEAD_LETTER` after 3 retries.

---

## Real-World Banking Challenges: Next-Gen Architecture

### A. Transit Account Hot-Spot Partitioning (High-TPS Scaling)
In high-throughput instant payment engines, every outbound transfer credits the central bank settlement transit account (`TRANSIT-001`). Under 5,000+ TPS, applying pessimistic locks (`FOR UPDATE`) or optimistic locks (`@Version`) to a single transit row creates an unbearable serialization bottleneck.

**Proposed Resolution:**
- **Sharded Transit Buckets:** Transit balances are striped across $N$ sub-accounts (e.g., `TRANSIT-001:SHARD:{0..15}`) determined by `hash(txId) % N`.
- Concurrent transactions write to independent shard rows with zero lock contention.
- A scheduled batch rollup consolidates shard balances into the master central bank reserve account without blocking the live payment hot path.

### B. Out-of-Order Asynchronous Callback Correlation
In production clearing systems (SPI Bacen / FedNow), clearing responses are asynchronous callbacks or webhook events (`pacs.002`). Under transient network jitter, an inbound callback may hit an application instance before the original dispatch thread finishes committing its initial state.

**Proposed Resolution:**
- **Staging Lease Buffer:** Inbound callbacks matching an `EndToEndId` that is not yet visible in `PAYMENT_SUBMITTED` state are placed in an ephemeral high-speed quarantine buffer (with a 250ms exponential backoff retry) rather than throwing an unhandled `EntityNotFoundException`.

---

## Project Structure

```text
com.nexor.payments/
├── domain/                      # Domain logic (zero framework dependencies)
│   ├── model/                  # Money, PaymentInstruction, PaymentStatus, EndToEndId
│   ├── ledger/                 # LedgerAccount, JournalEntry, PostingLeg, AccountType
│   ├── iso20022/               # pacs.008, pacs.002, pacs.004 models
│   └── exception/              # Domain-specific exceptions (InsufficientFunds, FraudRejection)
├── application/                # Ports and use-case orchestrators
│   ├── port/in/                # SubmitPaymentUseCase, command & response DTOs
│   ├── port/out/               # Repository, ClearingRail, EventPublisher, TransactionCoordinator
│   ├── saga/                   # PaymentSagaOrchestrator (Phase coordination)
│   ├── ledger/                 # LedgerIntegrityService (Historical posting audit)
│   ├── fraud/                  # FraudScreeningChain (Sanctions, Velocity, High-Value rules)
│   └── routing/                # SmartRailRouter (Pix, FedNow, BookTransfer)
└── infrastructure/             # Adapters and platform configuration
    ├── adapter/in/web/         # REST Controller (/api/v1/payments) & exception advice
    ├── adapter/out/persistence/# Spring Data JPA repositories & Outbox entity
    ├── adapter/out/messaging/  # OutboxRelayWorker (KafkaTemplate dispatch)
    ├── adapter/out/clearing/   # Clearing rail adapter & PaymentReconciliationWorker
    ├── observability/          # Micrometer / Prometheus metrics
    └── config/                 # Security filter chain & rate limiting
```

---

## Failure Scenarios & Invariant Verification

| Failure Mode | Root Cause | Nexor Prevention Mechanism | Test Verification |
| :--- | :--- | :--- | :--- |
| **Double-Debit Race** | Concurrent requests with identical idempotency key | SHA-256 fingerprint check + relational database unique lease lock | `IdempotencyConcurrencyTest` |
| **Dual-Write Inconsistency** | App crashes after DB commit but before broker publish | Transactional Outbox pattern saved in identical ACID transaction | `TransactionalSagaRollbackTest` |
| **Connection Starvation** | External rail latency blocks DB connection | External I/O executed strictly outside database transaction boundary | `PaymentSagaOrchestratorTest` |
| **Premature Chargeback** | Rail timeout treated as immediate payment failure | `PDNG` status moves payment to `PENDING_INVESTIGATION` with transit hold | `TimeoutReconciliationTest` |
| **Outbox Worker Race** | Multiple cluster pods relaying pending events concurrently | `SELECT ... FOR UPDATE SKIP LOCKED` claims unique rows | `OutboxMultiInstanceConcurrencyTest` |
| **Ledger Tampering** | Out-of-band direct database modification of balance column | `LedgerIntegrityService` audits balance against historical posting legs | `LedgerIntegrityTest` |
| **Stale Balance Overwrite** | Simultaneous debits on same account | Optimistic locking (`@Version`) rejects concurrent dirty writes | `LedgerOptimisticLockingRegressionTest` |

---

## Getting Started

### Prerequisites
- **Java 17 LTS**
- **Maven 3.9+**
- **Docker & Docker Compose**

### 1. Start Backing Infrastructure
```bash
docker compose up -d
```
Starts PostgreSQL 16 and Apache Kafka (KRaft mode).

### 2. Run Test Suite
```bash
# Unit, domain, and concurrency tests (46 tests)
mvn clean test

# Multi-process distributed idempotency test (spawns 3 distinct JVM processes against PostgreSQL)
mvn clean package -DskipTests
mvn failsafe:integration-test failsafe:verify
```

### 3. Run the Application
```bash
mvn spring-boot:run
```
- API Base: `http://localhost:8080`
- Actuator Health: `http://localhost:8080/actuator/health`
- Prometheus Metrics: `http://localhost:8080/actuator/prometheus`

---

## API Reference

### Submit Payment
`POST /api/v1/payments`

**Headers:**
- `X-API-Key: nexor-submitter-key`
- `Idempotency-Key: c9b29db4-4690-4cfa-89a1-8e01bf26e382`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "debtorAccount": "ACC-DEBTOR-001",
  "creditorAccount": "ACC-CREDITOR-002",
  "amount": "150.00",
  "currency": "BRL",
  "requestedRail": "PIX",
  "remittanceInformation": "Invoice payment 2026-09"
}
```

**Response (`201 Created` / `202 Accepted`):**
```json
{
  "transactionId": "tx_8f1a2c3d-4e5f",
  "endToEndId": "E99999001202609151830a1b2c3d4e5f",
  "debtorAccount": "ACC-DEBTOR-001",
  "creditorAccount": "ACC-CREDITOR-002",
  "amount": "150.00",
  "currency": "BRL",
  "rail": "PIX",
  "status": "SETTLED",
  "clearingReference": "CLR-SPI-2026-99018",
  "failureReason": null,
  "updatedAt": "2026-09-15T18:30:00Z"
}
```

---

## License

Distributed under the [MIT License](LICENSE).
