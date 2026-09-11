# Production Architecture & Operational Roadmap

This document outlines the target cloud architecture and operational roadmap for deploying the Nexor RTP Core in an enterprise production environment.

The codebase implements the core financial transaction engine: double-entry ledger invariants, deterministic payment state machines, discrete ACID Saga units of work, relational idempotency, and asynchronous reconciliation. This roadmap details the complementary cloud infrastructure, edge security, and platform concerns required for multi-region or high-availability operation.

> [!NOTE]
> **Engineering Status**: Target Cloud Architecture / Roadmap. Sections detailing AWS managed services, multi-region routing, and disaster recovery metrics (Target: RPO = 0 / RTO < 60s) specify infrastructure requirements, distinct from what is implemented and tested directly in the application code.

---

## Target Cloud Topology (AWS Multi-AZ)

```
                       ┌──────────────────────────────────────────────┐
                       │          Clearing Network (SPI / FedNow)     │
                       └──────────────────────────────────────────────┘
                                              ▲
                                      mTLS (RFC 8446)
                                              ▼
                             ┌──────────────────────────────────┐
                             │       AWS Direct Connect         │
                             └──────────────────────────────────┘
                                              │
                      ┌───────────────────────▼────────────────────────┐
                      │    AWS API Gateway / Cloudflare Enterprise     │
                      │   - WAF (OWASP Top 10 rules)                   │
                      │   - Token Bucket Rate Limiting (Redis Cluster) │
                      │   - Client Certificate Validation (mTLS)       │
                      └───────────────────────┬────────────────────────┘
                                              │ (Private Subnets)
    ┌─────────────────────────────────────────▼─────────────────────────────────────────┐
    │                      AWS EKS Cluster (Multi-AZ Nodes)                             │
    │                                                                                   │
    │      Pod (Instance A)              Pod (Instance B)              Pod (Instance C) │
    │  ┌─────────────────────┐       ┌─────────────────────┐       ┌──────────────────┐ │
    │  │   Nexor RTP Core    │       │   Nexor RTP Core    │       │  Nexor RTP Core  │ │
    │  │ - CorrelationFilter │       │ - CorrelationFilter │       │ - Outbox Relay   │ │
    │  │ - Local ACID UoW    │       │ - Local ACID UoW    │       │ - Reconciler     │ │
    │  └──────────┬──────────┘       └──────────┬──────────┘       └─────────┬────────┘ │
    └─────────────┼─────────────────────────────┼────────────────────────────┼──────────┘
                  │                             │                            │
          ┌───────▼─────────────────────────────▼────────────────────────────▼────────┐
          │   Amazon RDS Aurora PostgreSQL (Multi-AZ Single-Writer & Read Replicas)   │
          │  - Versioned Flyway Migrations (V1, V2, V3, V4)                           │
          │  - Write-Ahead Log (WAL) streaming via Debezium / CDC                     │
          │  - Target SLO: RPO = 0 / RTO < 30s automated failover                     │
          └──────────────────────────────────────┬────────────────────────────────────┘
                                                 │ Logical Replication
                                        ┌────────▼────────┐
                                        │ Debezium / CDC  │
                                        └────────┬────────┘
                                                 │
          ┌──────────────────────────────────────▼────────────────────────────────────┐
          │             Amazon MSK (Apache Kafka Cluster, 3+ Brokers Multi-AZ)        │
          │  - Confluent Schema Registry (Avro / Protobuf)                            │
          │  - Topic partitioning by Debtor Account Hash                              │
          │  - min.insync.replicas=2, acks=all                                        │
          │  - Dead Letter Queue (DLQ) with exponential backoff                       │
          └───────────────────────────────────────────────────────────────────────────┘
```

---

## Operational Readiness Matrix

| # | Dimension | Reference Core Status | Production Target |
| :-: | :--- | :--- | :--- |
| **1** | **Authentication & Authorization** | API Key filter with RBAC roles in Spring Security. | Strict mTLS for B2B/clearing connections using banking PKI. OAuth2/OIDC with asymmetric JWTs (RS256/EdDSA) and OPA (Open Policy Agent) for fine-grained authorization. |
| **2** | **Secret Management** | Environment variables with default dev fallbacks. | HashiCorp Vault or AWS Secrets Manager with dynamic rotation of database credentials and API keys via Kubernetes Secrets Store CSI Driver. |
| **3** | **Payload Non-Repudiation** | In-memory validation and SHA-256 fingerprinting. | Digital signatures on payment payloads (JWS / XML DSig) backed by FIPS 140-2 Level 3 Hardware Security Modules (HSMs). |
| **4** | **Message Streaming** | Embedded / Testcontainers Kafka (`spring-kafka`). | Amazon MSK across 3 Availability Zones, replication factor = 3, `min.insync.replicas = 2`, TLS in-transit and KMS encryption at rest. |
| **5** | **Observability** | Micrometer + Prometheus Actuator endpoints. | OpenTelemetry Collector pipeline, Grafana RED dashboards (Rate, Errors, Duration), and SLO alerts via PagerDuty. |
| **6** | **Cloud Deployment** | Dockerfile and Docker Compose. | Helm charts with Kustomize, GitOps deployment via ArgoCD supporting Canary and Blue/Green progressive rollouts. |
| **7** | **CI/CD Automation** | Maven lifecycle (`mvn clean test`). | GitHub Actions pipeline running the 47 automated tests, ArchUnit architectural rules, SonarQube quality gates, and Trivy vulnerability scanning. |
| **8** | **Infrastructure as Code** | Docker Compose for local development. | Terraform / OpenTofu modules (VPC, EKS, Aurora, MSK) with remote state locking in S3 + DynamoDB. |
| **9** | **Load & Stress Testing** | Concurrent thread tests and k6 load script. | Automated performance pipelines running `k6-payment-load-test.js` against staging environments to validate throughput and p95/p99 latency under load. |
| **10**| **Chaos Engineering** | Unit and integration failure mode tests. | Chaos Mesh or AWS FIS injecting network packet drops, node failovers, and broker partition loss during active traffic. |
| **11**| **Data Partitioning** | Single table without native partitioning. | PostgreSQL range partitioning by month for `payment_instructions` and `posting_legs`; Kafka topics partitioned by debtor account hash for causal ordering. |
| **12**| **Dead Letter Queue (DLQ)** | `OutboxRelayWorker` with `SKIP LOCKED`, exponential backoff, and DLQ state. | Kafka retry topics (`payments.events.retry-1m`, `payments.events.retry-5m`) and dead-letter topic with replay tooling and audit logging. |
| **13**| **Schema Evolution** | JSON payloads in database and Java DTOs. | Confluent Schema Registry with Avro/Protobuf definitions enforcing Full Transitive compatibility. |
| **14**| **Distributed Tracing** | MDC logging via `CorrelationIdFilter`. | W3C TraceContext (`traceparent`) propagated through HTTP headers into Kafka record headers, visualized in Jaeger / Grafana Tempo. |
| **15**| **Rate Limiting** | Fixed-window limiter (`RateLimitingFilter`, 100 req/min). | Distributed Token Bucket via Redis Cluster at API Gateway / ingress layer with per-IP, per-key, and per-account rate limits. |
| **16**| **Disaster Recovery (DR)** | Multi-AZ relational model. | Active-Passive (Pilot Light) or Active-Active multi-region routing via Route 53 with automated failover: Target RPO = 0 and RTO < 60 seconds. |
| **17**| **High Availability (HA)** | Multi-threaded concurrency handling. | EKS PodDisruptionBudgets, topology spread constraints across zones, and Horizontal Pod Autoscaling (HPA) based on CPU and Kafka consumer lag. |
| **18**| **Security Scanning** | Static checks and clean compiler flags. | SAST (SonarQube), SCA (Trivy/Snyk), and DAST (OWASP ZAP) integrated into continuous integration. |
| **19**| **Database Migrations** | Versioned Flyway SQL migrations (`V1` through `V4`). | Immutable Flyway migrations applied before application boot, with `ddl-auto: validate` preventing Hibernate runtime alterations. |
| **20**| **Runtime Tuning** | Standard JVM settings for dev/test. | Generational ZGC for low-latency GC pauses (<1ms), HikariCP pool sizing, graceful shutdown hooks, and disabled Spring Open-in-View. |

---

## Summary

Nexor RTP Core implements the transactional core of a real-time payment engine: mathematical double-entry balance consistency, state determinism, and distributed idempotency.

The cloud infrastructure, observability pipelines, and deployment practices described in this document outline how the core transitions into an enterprise production environment.
