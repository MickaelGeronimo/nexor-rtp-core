# Nexor RTP Core — Architecture Diagrams

## C4 Level 1 — System Context

```mermaid
C4Context
    title System Context — Nexor RTP Core

    Person(banker, "Bank Engineer / Client Application", "Initiates real-time payments via REST API")
    
    System(nexor, "Nexor RTP Core", "Orchestrates real-time payments with double-entry ledger, fraud screening, and transactional outbox")
    
    System_Ext(spi, "SPI / FedNow / SWIFT", "Central Bank clearing rail for interbank settlement")
    System_Ext(kafka, "Apache Kafka", "Event streaming — downstream consumers receive payment events")
    System_Ext(prometheus, "Prometheus / Grafana", "Metrics collection and alerting")

    Rel(banker, nexor, "POST /api/v1/payments", "HTTPS + X-API-Key")
    Rel(nexor, spi, "ISO 20022 pacs.008 / pacs.002", "mTLS")
    Rel(nexor, kafka, "Publishes domain events", "Transactional Outbox pattern")
    Rel(prometheus, nexor, "Scrapes /actuator/prometheus", "HTTP")
```

---

## C4 Level 2 — Container Diagram

```mermaid
C4Container
    title Container Diagram — Nexor RTP Core

    Person(client, "Bank Client Application")
    
    Container(api, "Payment REST API", "Spring Boot / Java 17", "Accepts payment requests, enforces auth + rate limiting")
    ContainerDb(db, "PostgreSQL", "Relational Database", "Ledger accounts, journal entries, payment instructions, outbox events, idempotency keys")
    Container(relay, "Outbox Relay Worker", "Spring Scheduling", "Polls PENDING outbox events and dispatches to Kafka")
    Container(reconcile, "Reconciliation Worker", "Spring Scheduling", "Resolves PENDING_INVESTIGATION payments via clearing rail inquiry")
    Container_Ext(kafka, "Apache Kafka", "Message Broker", "Receives domain events for downstream consumers")
    Container_Ext(clearing, "SPI / FedNow", "Clearing Rail", "Interbank settlement")

    Rel(client, api, "POST /api/v1/payments", "HTTPS")
    Rel(api, db, "Reads/writes via JPA", "JDBC")
    Rel(relay, db, "Polls PENDING events", "JDBC")
    Rel(relay, kafka, "kafkaTemplate.send()", "TCP")
    Rel(reconcile, db, "Reads PENDING_INVESTIGATION", "JDBC")
    Rel(reconcile, clearing, "pacs.028 inquiry", "HTTPS")
    Rel(api, clearing, "pacs.008 dispatch", "HTTPS")
```

---

## C4 Level 3 — Component Diagram (Hexagonal Architecture)

```mermaid
graph TB
    subgraph "Inbound Adapters [in]"
        WEB["PaymentController\n(REST / Spring MVC)"]
        SEC["SecurityFilterChain\n(API Key + RBAC)"]
        RATE["RateLimitingFilter"]
        CORR["CorrelationIdFilter\n(MDC Tracing)"]
    end

    subgraph "Application Core [domain + application]"
        UC["SubmitPaymentUseCase\n(port/in)"]
        SAGA["PaymentSagaOrchestrator\n(saga)"]
        FRAUD["FraudScreeningChain\n(fraud)"]
        ROUTER["SmartRailRouter\n(routing)"]
        LEDGER_SVC["LedgerIntegrityService\n(ledger)"]

        subgraph "Domain Model"
            PI["PaymentInstruction\n(aggregate + FSM)"]
            LA["LedgerAccount\n(domain entity)"]
            JE["JournalEntry\n(immutable)"]
            MONEY["Money\n(value object)"]
        end
    end

    subgraph "Outbound Ports [port/out]"
        PAY_PORT["PaymentRepositoryPort"]
        LEDGER_PORT["LedgerRepositoryPort"]
        CLEAR_PORT["ClearingRailPort"]
        EVENT_PORT["EventPublisherPort"]
        IDEM_PORT["IdempotencyStoragePort"]
        TX_PORT["PaymentTransactionCoordinatorPort"]
    end

    subgraph "Outbound Adapters [out]"
        JPA_PAY["JpaPaymentRepositoryAdapter"]
        JPA_LEDGER["JpaLedgerRepositoryAdapter"]
        JPA_EVENT["JpaEventPublisherAdapter\n(Transactional Outbox Writer)"]
        JPA_IDEM["JpaIdempotencyStorageAdapter"]
        JPA_TX["JpaPaymentTransactionCoordinatorAdapter\n(@Transactional ACID UoW)"]
        MOCK_CLEAR["MockCentralBankClearingRail"]
        OUTBOX_RELAY["OutboxRelayWorker\n(KafkaTemplate.send)"]
    end

    subgraph "Infrastructure"
        DB[(PostgreSQL / H2)]
        KAFKA[(Apache Kafka)]
    end

    WEB --> UC
    UC --> SAGA
    SAGA --> FRAUD
    SAGA --> ROUTER
    SAGA --> TX_PORT
    TX_PORT --> JPA_TX
    JPA_TX --> PAY_PORT & LEDGER_PORT & EVENT_PORT
    PAY_PORT --> JPA_PAY
    LEDGER_PORT --> JPA_LEDGER
    EVENT_PORT --> JPA_EVENT
    IDEM_PORT --> JPA_IDEM
    CLEAR_PORT --> MOCK_CLEAR
    JPA_PAY & JPA_LEDGER & JPA_EVENT & JPA_IDEM --> DB
    OUTBOX_RELAY --> DB
    OUTBOX_RELAY --> KAFKA
```

---

## Payment State Machine

```mermaid
stateDiagram-v2
    [*] --> VALIDATED : PaymentInstruction created + validated

    VALIDATED --> FRAUD_APPROVED : FraudScreeningChain.evaluate() = APPROVED
    VALIDATED --> FRAUD_REJECTED : FraudScreeningChain.evaluate() = REJECTED

    FRAUD_APPROVED --> FUNDS_RESERVED : Ledger hold created\nDebtor → Transit (ACID)

    FUNDS_RESERVED --> SUBMITTED_TO_CLEARING : BOOK_TRANSFER skips this
    FUNDS_RESERVED --> SETTLED : BOOK_TRANSFER\nTransit → Creditor (ACID)

    SUBMITTED_TO_CLEARING --> SETTLED : pacs.002 = ACSC\nClearing settlement confirmed
    SUBMITTED_TO_CLEARING --> PENDING_INVESTIGATION : pacs.002 = PDNG\nTimeout ≠ Failure (ADR-005)
    SUBMITTED_TO_CLEARING --> REJECTED_CLEARING : pacs.002 = RJCT\nAuthoritative rejection

    PENDING_INVESTIGATION --> SETTLED : Reconciliation worker confirms\nDelayed settlement
    PENDING_INVESTIGATION --> COMPENSATING : Reconciliation confirms\nPermanent failure

    REJECTED_CLEARING --> COMPENSATING : Saga compensation triggered

    COMPENSATING --> COMPENSATED : Reversal journal entry created\nTransit → Debtor (ACID)

    SETTLED --> [*]
    COMPENSATED --> [*]
    FRAUD_REJECTED --> [*]
```

> **Note on PENDING_INVESTIGATION:** A clearing timeout does NOT trigger automatic compensation.
> Funds remain in the transit buffer pending async reconciliation inquiry (pacs.028).
> This is the correct behavior per ISO 20022 and ADR-005. Premature compensation on timeout
> would result in double-refunds when the clearing rail eventually confirms settlement.

---

## Transactional Outbox Flow

```mermaid
sequenceDiagram
    participant Saga as PaymentSagaOrchestrator
    participant TxCoord as JpaPaymentTransactionCoordinatorAdapter
    participant DB as PostgreSQL
    participant Relay as OutboxRelayWorker
    participant Kafka as Apache Kafka

    Note over Saga,DB: Single @Transactional boundary
    Saga->>TxCoord: executeAtomicReservation(instruction, debtor, transit, holdEntry, payload)
    TxCoord->>DB: UPDATE ledger_accounts SET balance=... (debtor)
    TxCoord->>DB: UPDATE ledger_accounts SET balance=... (transit)
    TxCoord->>DB: INSERT INTO journal_entries
    TxCoord->>DB: INSERT INTO payment_instructions
    TxCoord->>DB: INSERT INTO outbox_events (status=PENDING)
    TxCoord-->>Saga: committed ✅ (or full rollback on any failure)

    Note over Relay,Kafka: Async — runs every 2000ms
    Relay->>DB: SELECT * FROM outbox_events WHERE status='PENDING' OR (status='FAILED' AND retry_count < 3) ORDER BY created_at ASC
    Relay->>Kafka: kafkaTemplate.send(topic, eventJson)
    Kafka-->>Relay: send future resolved (Broker Ack)
    Relay->>DB: UPDATE outbox_events SET status='PUBLISHED', published_at=NOW()
    
    Note over Relay: On Kafka failure: retry_count++ and status='FAILED'\nAfter 3 retries: status='DEAD_LETTER' (quarantined)
```

---

## Reconciliation Worker Flow (ADR-006: Scoped ACID Boundaries)

```mermaid
sequenceDiagram
    participant Recon as PaymentReconciliationWorker
    participant Clearing as Central Bank Clearing (Bacen SPI)
    participant TxCoord as JpaPaymentTransactionCoordinatorAdapter
    participant DB as PostgreSQL

    Note over Recon,Clearing: Phase 1: External Network Call (ZERO DB connections held open)
    Recon->>Clearing: queryPaymentStatus(rail, endToEndId) [pacs.028 inquiry]
    Clearing-->>Recon: Pacs002StatusReport (ACSC or RJCT)

    Note over Recon,DB: Phase 2: Local Scoped ACID Unit of Work (@Transactional)
    alt Authoritative Confirmation: ACSC (Settled)
        Recon->>TxCoord: executeAtomicClearingSettlement(instruction, payload)
        TxCoord->>DB: UPDATE payment_instructions SET status='SETTLED'
        TxCoord->>DB: INSERT INTO outbox_events ('PAYMENT_SETTLED_CLEARING')
        TxCoord-->>Recon: committed ✅
    else Authoritative Confirmation: RJCT (Rejected)
        Recon->>TxCoord: executeAtomicCompensation(instruction, debtor, transit, reversalEntry, payload)
        TxCoord->>DB: UPDATE ledger_accounts (reversal legs: transit debit, debtor credit)
        TxCoord->>DB: INSERT INTO journal_entries (RECON reversal)
        TxCoord->>DB: UPDATE payment_instructions SET status='COMPENSATED'
        TxCoord->>DB: INSERT INTO outbox_events ('PAYMENT_COMPENSATED_REVERSED')
        TxCoord-->>Recon: committed ✅
    end
```

---

## Sharded Transit Buckets — Concurrency & Hotspot Elimination (ADR-010)

```mermaid
graph TD
    subgraph Inbound["Concurrent Incoming Payments (5,000+ TPS)"]
        T1["Payment Tx-1\n(hash: 0x4A1F)"]
        T2["Payment Tx-2\n(hash: 0x8C3B)"]
        T3["Payment Tx-3\n(hash: 0x12FE)"]
        TN["Payment Tx-N\n(hash: 0x99D1)"]
    end

    subgraph Router["Deterministic Shard Partitioner"]
        HASH["bucketIndex = (Math.abs(txId.hashCode()) % 16) + 1\nAccountId.of(String.format('TRANSIT-%03d', bucketIndex), '0001', 'CLEARING')"]
    end

    subgraph Ledger["PostgreSQL Sharded Transit Accounts (ledger_accounts)"]
        B1["TRANSIT-001\n(Row lock 1)"]
        B2["TRANSIT-002\n(Row lock 2)"]
        B3["TRANSIT-003\n(Row lock 3)"]
        DOTS["..."]
        B16["TRANSIT-016\n(Row lock 16)"]
    end

    subgraph Clearing["Central Bank SPI / FedNow Settlement"]
        BACEN["Central Bank Clearing Settlement\n(pacs.008 Dispatch)"]
    end

    T1 --> HASH
    T2 --> HASH
    T3 --> HASH
    TN --> HASH

    HASH -->|Bucket 1| B1
    HASH -->|Bucket 2| B2
    HASH -->|Bucket 3| B3
    HASH -->|...| DOTS
    HASH -->|Bucket 16| B16

    B1 -.-> BACEN
    B2 -.-> BACEN
    B3 -.-> BACEN
    B16 -.-> BACEN
```

---

## Asynchronous Callback Race Condition Handling (Staging Lease Buffer)

```mermaid
sequenceDiagram
    participant Bacen as Central Bank (SPI Webhook)
    participant Webhook as WebhookController
    participant Lease as StagingLeaseBuffer (Memory/DB)
    participant Saga as PaymentSagaWorker
    participant DB as PostgreSQL

    Note over Bacen,Webhook: Network jitter causes pacs.002 to arrive BEFORE pacs.008 commit
    Bacen->>Webhook: POST /webhooks/pacs002 (EndToEndId: E2E-9988)
    Webhook->>DB: findPaymentByEndToEndId("E2E-9988")
    DB-->>Webhook: empty / NOT_FOUND (Saga dispatch transaction still in-flight)
    
    Note over Webhook,Lease: Staging Lease Buffer intercepts 404
    Webhook->>Lease: stageCallback(endToEndId="E2E-9988", payload, ttl=5000ms)
    Webhook-->>Bacen: 202 Accepted (Callback held in quarantine lease)
    
    Note over Saga,DB: Saga thread finishes network dispatch and commits initial record
    Saga->>DB: COMMIT PaymentInstruction (status=SUBMITTED_TO_CLEARING)
    
    Note over Lease,DB: Poller / Correlator picks up staged callback
    Lease->>DB: correlateStagedCallback("E2E-9988")
    DB-->>Lease: PaymentInstruction found!
    Lease->>DB: UPDATE PaymentInstruction SET status='SETTLED'
    Lease->>DB: INSERT INTO outbox_events ('PAYMENT_SETTLED')
    Lease->>DB: DELETE FROM staged_callbacks WHERE end_to_end_id='E2E-9988'
```

