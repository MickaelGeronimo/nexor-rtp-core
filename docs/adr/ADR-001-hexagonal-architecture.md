# ADR-001: Hexagonal Architecture (Ports & Adapters)

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

Financial payment switches and real-time rail orchestrators interface with diverse external protocols (ISO 20022 XML/JSON, proprietary clearing network APIs, relational databases, Kafka message brokers). 

Coupling business logic directly to Spring Data repositories, JPA annotations, HTTP servlets, or messaging clients leads to:
1. Difficulty running pure domain unit tests without booting Spring application contexts or databases.
2. Inadvertent leaking of database transaction boundaries into external network operations.
3. Tight coupling between clearing transport protocols and internal payment state machines.

## Decision

Adopt strict **Hexagonal Architecture (Ports and Adapters)** dividing the codebase into three decoupled layers:

1. **Domain Layer (`domain`)**:
   - Zero framework dependencies (no Spring, no Hibernate, no Jackson annotations).
   - Core aggregates (`PaymentInstruction`), value objects (`Money`, `TransactionId`, `EndToEndId`), entities (`LedgerAccount`, `JournalEntry`), and domain exceptions.
   - Enforces financial invariants (balance validity, state transitions).

2. **Application Layer (`application`)**:
   - Inbound Ports (`port/in`): Interfaces defining use cases (`SubmitPaymentUseCase`).
   - Outbound Ports (`port/out`): Interfaces defining operations required from external systems (`ClearingRailPort`, `LedgerRepositoryPort`, `EventPublisherPort`, `IdempotencyStoragePort`, `PaymentTransactionCoordinatorPort`).
   - Sagas and orchestration services (`PaymentSagaOrchestrator`, `FraudScreeningChain`, `SmartRailRouter`).

3. **Infrastructure Layer (`infrastructure`)**:
   - Inbound Adapters (`adapter/in`): Web controllers (`PaymentController`), security filters (`RateLimitingFilter`, `ApiKeyAuthenticationFilter`).
   - Outbound Adapters (`adapter/out`): Persistence (`JpaLedgerRepositoryAdapter`, `JpaPaymentRepositoryAdapter`), messaging (`OutboxRelayWorker`), clearing rail integrations (`MockCentralBankClearingRail`, `PaymentReconciliationWorker`).

Enforce this separation at build time using **ArchUnit** tests in `HexagonalArchitectureArchUnitTest`.

## Consequences

- Domain models are 100% testable with pure JUnit tests running in milliseconds.
- Infrastructure technologies (PostgreSQL, Kafka, Bacen SPI mock) can be substituted or upgraded without altering transaction rules.
- Build fails via ArchUnit if any class in `domain` imports from `org.springframework` or `jakarta.persistence`.
