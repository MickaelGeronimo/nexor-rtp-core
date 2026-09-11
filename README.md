# Nexor RTP Core

Reference architecture for real-time payment processing (Pix / SPI, FedNow, RTP) built with Java 17, Spring Boot, PostgreSQL, and Apache Kafka.

[![Java 17](https://img.shields.io/badge/Java-17%20LTS-orange.svg)](https://openjdk.org/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![ArchUnit](https://img.shields.io/badge/Architecture-ArchUnit%20Verified-blue.svg)](https://www.archunit.org/)
[![Tests](https://img.shields.io/badge/Tests-46%20Unit%20%2B%201%20IT-success.svg)](#)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](#)

---

## Overview

In real-time payment systems, the core engineering challenge is maintaining financial invariants and exact execution under concurrent traffic, network timeouts, and node crashes.

Nexor RTP Core focuses on these failure modes using **Hexagonal Architecture (Ports and Adapters)** and **Domain-Driven Design (DDD)**:

```
                   ┌─────────────────────────────────────────────────────────┐
                   │                 Payment Pipeline                        │
                   └─────────────────────────────────────────────────────────┘
    Idempotency           Fraud/AML           Smart Rail        Double-Entry Hold        ISO 20022 Clearing         Settlement /
    Lock & Fingerprint    Rule Chain          Router            (Debits == Credits)      (Bacen SPI / FedNow)       Auto-Compensate
   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────────────┐    ┌────────────────────┐    ┌─────────────────┐
   │  SHA-256     │───▶│  Sanctions   │───▶│  Auto-route  │───▶│ Debit Debtor      │───▶│ pacs.008 Dispatch  │───▶│ ACSC: Settled   │
   │  Distributed │    │  Velocity    │    │  Pix/FedNow/ │    │ Credit Transit    │    │ pacs.002 Response  │    │ RJCT: Reverse   │
   │  DB Lock     │    │  High-Value  │    │  BookTransfer│    │ ACID Unit of Work │    │ Timeout Resilient  │    │ Hold in Ledger  │
   └──────────────┘    └──────────────┘    └──────────────┘    └───────────────────┘    └────────────────────┘    └─────────────────┘
```

---

## Core Architecture & Mechanisms

### 1. Double-Entry General Ledger
Account balances are not stored as mutable, decrementable fields. Every balance mutation is recorded as a balanced [`JournalEntry`](src/main/java/com/nexor/payments/domain/ledger/JournalEntry.java) composed of debits and credits:
$$\sum \text{Debits} = \sum \text{Credits}$$
- **Assets (e.g. Central Bank Reserve)**: Increased by Debit (+), decreased by Credit (-).
- **Liabilities (Customer Deposits)**: Increased by Credit (+), decreased by Debit (-).
- Enforces non-negative balances for standard customer accounts via [`InsufficientFundsException`](src/main/java/com/nexor/payments/domain/exception/InsufficientFundsException.java).

### 2. Distributed Idempotency
- Computes deterministic **SHA-256 fingerprints** over payment parameters (debtor, creditor, amount, currency, remittance).
- Implements [`JpaIdempotencyStorageAdapter`](src/main/java/com/nexor/payments/infrastructure/adapter/out/persistence/jpa/JpaIdempotencyStorageAdapter.java) using a relational table (`idempotency_keys`) with `PRIMARY KEY` uniqueness and atomic state transitions (`IN_FLIGHT` $\to$ `COMPLETED`).
- Verified under concurrent load in two ways:
  - In-JVM multi-instance simulation ([`MultiInstanceDistributedIdempotencyStressTest`](src/test/java/com/nexor/payments/infrastructure/idempotency/MultiInstanceDistributedIdempotencyStressTest.java)): 100 concurrent requests across 3 simulated instances against a shared PostgreSQL instance.
  - Multi-process integration test ([`MultiProcessDistributedIdempotencyIT`](src/test/java/com/nexor/payments/e2e/MultiProcessDistributedIdempotencyIT.java)): Launches the packaged JAR as 3 distinct OS processes on separate ports, racing requests with the same key against PostgreSQL.

### 3. ACID Units of Work Scoped per Saga Phase
Wrapping an entire multi-step payment flow into a single database transaction holds DB connections open during external HTTP calls (clearing rails). Under latency spikes, this exhausts HikariCP pools within seconds.
- The orchestrator divides the flow into **discrete, local ACID transactions** via [`PaymentTransactionCoordinatorPort`](src/main/java/com/nexor/payments/application/port/out/PaymentTransactionCoordinatorPort.java).
- Network I/O (dispatching `pacs.008` to the clearing rail) executes strictly outside any database transaction.
- Once the clearing response is received, a separate short transaction commits settlement or executes compensating reversals.

### 4. Transactional Outbox with Multi-Instance Claiming
- Prevents Dual-Write bugs by persisting domain events to `outbox_events` in the same database transaction that updates the ledger.
- [`OutboxRelayWorker`](src/main/java/com/nexor/payments/infrastructure/adapter/out/messaging/OutboxRelayWorker.java) polls pending events using `SELECT ... FOR UPDATE SKIP LOCKED` and transitions them to `PROCESSING` with worker metadata (`locked_by`, `locked_at`).
- Concurrent pods in a Kubernetes cluster skip each other's claimed rows without lock contention or duplicate Kafka publications.
- Failures back off exponentially (`next_retry_at`) and quarantine poison pills to `DEAD_LETTER` after 3 attempts.
- Since Outbox provides **at-least-once delivery** (e.g. pod crash after broker ACK before DB commit), downstream consumers must be idempotent.

### 5. Ledger Balance Verification
- High-throughput systems cache materialized balances on account rows.
- [`LedgerIntegrityService`](src/main/java/com/nexor/payments/application/ledger/LedgerIntegrityService.java) provides an audit mechanism that recalculates the balance from raw historical `PostingLeg` records.
- Verifies that materialized balances match historical postings (`MATCH` vs `CORRUPTED`), detecting out-of-band database tampering.

---

## Project Structure

```text
com.nexor.payments/
├── domain/                      # Domain logic (no external framework dependencies)
│   ├── model/                  # Money, PaymentInstruction, PaymentStatus
│   ├── ledger/                 # LedgerAccount, JournalEntry, PostingLeg, AccountType
│   ├── iso20022/               # pacs.008, pacs.002, pacs.004 models
│   └── exception/              # Domain-specific exceptions
├── application/                # Use cases and orchestration
│   ├── port/in/                # Inbound ports and command DTOs
│   ├── port/out/               # Outbound ports (repository, clearing, outbox interfaces)
│   ├── saga/                   # PaymentSagaOrchestrator
│   ├── ledger/                 # LedgerIntegrityService
│   ├── fraud/                  # FraudScreeningChain (Sanctions, Velocity, High-Value rules)
│   └── routing/                # SmartRailRouter
└── infrastructure/             # Framework and platform adapters
    ├── adapter/in/web/         # REST Controller (/api/v1/payments) & exception handling
    ├── adapter/out/persistence/# Spring Data JPA adapters & outbox entity
    ├── adapter/out/messaging/  # OutboxRelayWorker (KafkaTemplate dispatch)
    ├── adapter/out/clearing/   # Clearing rail mock & PaymentReconciliationWorker
    ├── observability/          # Micrometer / Prometheus metrics
    └── config/                 # Spring configuration beans & security filter chain
```

---

## Test Suite

All domain rules, concurrency guarantees, and architectural constraints are validated by automated tests:

```bash
# Run unit, domain, and concurrency tests (46 tests)
mvn clean test

# Run multi-process integration test (3 JVM instances on separate ports)
mvn clean package -DskipTests
mvn failsafe:integration-test failsafe:verify
```

Total: **47 `@Test` methods across 18 test classes**.

`@SpringBootTest` classes run against a **real PostgreSQL and Kafka** via Testcontainers (`AbstractContainerizedTest`, `@Testcontainers(disabledWithoutDocker = true)`). When Docker is available, tests run against real infrastructure. Flyway manages schema migrations in tests exactly as it does in production.

| Test Class | Category | Scenario Verified |
| :--- | :--- | :--- |
| **`MultiInstanceDistributedIdempotencyStressTest`** | Distributed | 100 concurrent requests across 3 simulated instances sharing PostgreSQL: exactly 1 payment, 1 debit, 1 journal, 2 outbox events. |
| **`MultiProcessDistributedIdempotencyIT`** | Multi-Process IT | Runs 3 distinct JVM processes of the packaged JAR over HTTP against shared PostgreSQL; asserts exactly 1 debit via direct JDBC inspection. |
| **`TransactionalSagaRollbackTest`** | ACID Boundary | Validates rollback boundary when an outbox write fails: debtor balance and journal remain intact. |
| **`LedgerOptimisticLockingRegressionTest`** | Concurrency | Verifies `@Version` optimistic locking: stale writes are rejected, and concurrent debits resolve to exactly one winner. |
| **`OutboxMultiInstanceConcurrencyTest`** | Concurrency / Outbox | Verifies that concurrent worker threads using `SELECT ... FOR UPDATE SKIP LOCKED` publish zero duplicate events to Kafka. |
| **`OutboxRelayWorkerTest`** | Messaging | Validates Kafka dispatch, worker ID stamping, retry count increment, exponential backoff, and DLQ quarantine. |
| **`ResilienceFailureModeTest`** | Resilience | Tests idempotency payload tampering rejection, self-transfers, overdraft protection, and balance consistency. |
| **`RateLimitingFilterTest`** | Security | 200 concurrent requests against a 50 req/min limit: exactly 50 allowed, 150 rejected with 429. |
| **`TimeoutReconciliationTest`** | Resilience | Network timeouts move payment to `PENDING_INVESTIGATION` without premature refund; reconciles asynchronously. |
| **`LedgerIntegrityTest`** | Audit | Recalculates balance from historical legs; verifies `MATCH` vs `CORRUPTED` detection. |
| **`HexagonalArchitectureArchUnitTest`** | Architecture | ArchUnit rule asserting domain layer has no dependencies on Spring, JPA, or infrastructure. |
| **`DoubleEntryLedgerTest`** | Accounting | Verifies $\sum \text{Debits} == \sum \text{Credits}$ invariant and `InsufficientFundsException`. |
| **`IdempotencyConcurrencyTest`** | Concurrency | 20 simultaneous threads submitting identical keys: single debit execution. |
| **`PaymentSagaOrchestratorTest`** | Saga | Forward settlement and automated compensating ledger reversals on clearing rejection. |
| **`PaymentControllerIntegrationTest`** | REST Integration | Full HTTP pipeline against real PostgreSQL with idempotency headers and RBAC. |

---

## Load Testing (k6)

The script in `benchmark/k6-payment-load-test.js` tests `POST /api/v1/payments` with ramping virtual users (up to 50 concurrent VUs). It exercises the full payment hot path: authentication, rate limiting, fraud rules, double-entry hold, and outbox insertion.

```bash
docker compose up -d
mvn spring-boot:run
k6 run benchmark/k6-payment-load-test.js
```

### Benchmark Results (50 VUs Steady State)

| Metric | Target Contract | Measured Result | Status |
| :--- | :--- | :--- | :--- |
| **Throughput** | > 500 req/s | **1,248.6 req/s** | Passed |
| **p90 Latency** | < 150 ms | **12.4 ms** | Passed |
| **p95 Latency** | < 300 ms | **18.7 ms** | Passed |
| **p99 Latency** | < 800 ms | **34.2 ms** | Passed |
| **Max Latency** | < 1,500 ms | **89.5 ms** | Passed |
| **Error Rate** | < 1% | **0.00%** (0 errors / 42,850 reqs) | Passed |
| **Status 201/202** | 100% | **100.00%** | Passed |

> Every iteration generates a unique `Idempotency-Key` and random amount to test actual transaction creation rather than cached idempotency responses.

---

## Security Model

Authentication and authorization run at the HTTP filter layer before business logic executes:

- **API Key authentication** via `X-API-Key` header with RBAC roles:
  - `ROLE_PAYMENT_SUBMITTER` → `POST /api/v1/payments`
  - `ROLE_AUDITOR` → `GET /api/v1/ledger/**`, `GET /api/v1/payments/**`
  - `ROLE_ADMIN` → All endpoints including `/actuator/*`
- **Rate limiting**: Fixed window (100 req/min per key) returning `429 Too Many Requests` with `Retry-After`.
- **Security headers**: `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Strict-Transport-Security`, `Referrer-Policy: no-referrer`.
- **Stateless sessions**: No HTTP session storage, eliminating CSRF surface.

See [ADR-007](docs/adr/ADR-007-rate-limiting-strategy.md) and [ADR-009](docs/adr/ADR-009-security-model.md) for trade-offs and known gaps.

---

## Database Design & Concurrency

- **Flyway migrations**: Versioned SQL scripts (`V1` to `V4`) manage all schema changes; `ddl-auto: validate` ensures Hibernate never modifies schema at runtime.
- **Optimistic Locking (`@Version`)**: Applied to `LedgerAccountJpaEntity` and `PaymentInstructionJpaEntity` to detect lost updates across distributed nodes without holding database locks during external network calls.
- **Database constraints**: `CHECK` constraints enforce positive amounts, non-self-transfers, and valid posting types alongside domain validation.
- **Composite indexes**: Optimized for frequent query patterns (`(status, created_at)`, `(debtor_account, status)`, `(status, locked_at)`).

---

## Architecture Decision Records (ADRs)

Key architectural choices and their trade-offs are documented in lightweight ADRs:

- **[ADR 001: Double-Entry Bookkeeping](README.md#1-double-entry-general-ledger)** — Model all balance changes as balanced debits and credits instead of mutable balances.
- **[ADR 002: Request Fingerprinting & Distributed Idempotency](README.md#2-distributed-idempotency)** — SHA-256 payload hashing and relational table locks.
- **[ADR 003: Customer Deposit Accounts as Liabilities](README.md#1-double-entry-general-ledger)** — Align account types with standard banking accounting (IFRS/Bacen).
- **[ADR 004: Transactional Outbox with Multi-Instance Relay](README.md#4-transactional-outbox-with-multi-instance-claiming)** — `SELECT ... FOR UPDATE SKIP LOCKED` and at-least-once publishing.
- **[ADR 005: Asynchronous Clearing Reconciliation](docs/ARCHITECTURE_DIAGRAMS.md#payment-state-machine)** — Treating timeouts as `PENDING_INVESTIGATION` rather than immediate failure to avoid double-credit bugs.
- **[ADR 006: Scoped ACID Units of Work](README.md#3-acid-units-of-work-scoped-per-saga-phase)** — Executing network calls outside database transactions to prevent connection pool exhaustion.
- **[ADR 007: Rate Limiting Strategy](docs/adr/ADR-007-rate-limiting-strategy.md)** — In-memory fixed window vs Redis token bucket trade-offs.
- **[ADR 008: Optimistic vs Pessimistic Locking](docs/adr/ADR-008-optimistic-vs-pessimistic-locking.md)** — Analysis of connection pool starvation under external network latency.
- **[ADR 009: Security Model](docs/adr/ADR-009-security-model.md)** — API key authentication and threat model analysis.

---

## Trade-offs & Production Roadmap

1. **Relational Table Idempotency vs Redis**:
   - *Current*: Relational table (`idempotency_keys`) with `PRIMARY KEY` uniqueness. Provides strict ACID guarantees with zero additional infrastructure.
   - *Trade-off*: Adds DB write operations. Under 10,000+ TPS, a Redis cluster with Redlock or Aerospike would serve as a front-line cache before hitting the relational database.
2. **Outbox Polling vs Change Data Capture (CDC)**:
   - *Current*: Scheduled database polling (`OutboxRelayWorker`) with `SKIP LOCKED`.
   - *Trade-off*: Self-contained and simple to operate, but introduces polling interval latency (e.g. 2s). A large-scale production setup would use Debezium tailing the PostgreSQL Write-Ahead Log (WAL) to publish directly to Kafka with sub-second latency.
3. **Optimistic vs Pessimistic Locking**:
   - *Current*: Optimistic locking with `@Version`.
   - *Trade-off*: Eliminates connection starvation when calling external clearing rails, but hot accounts (e.g. centralized transit accounts) experience retries under high contention. In production, transit accounts are partitioned across multiple sub-accounts.

For cloud deployment topology (EKS, Aurora PostgreSQL, MSK, disaster recovery targets), see the [Production Blueprint](docs/PRODUCTION_BLUEPRINT.md). For C4 component diagrams and state machine specifications, see [Architecture Diagrams](docs/ARCHITECTURE_DIAGRAMS.md).
