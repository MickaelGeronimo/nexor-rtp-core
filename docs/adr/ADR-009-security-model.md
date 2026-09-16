# ADR-009: Security Model — API Key Authentication + RBAC

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

The payment API handles financially sensitive operations. We need authentication and authorization that:
1. Is demonstrably correct (verifiable without an external IdP)
2. Demonstrates the structural pattern used in production bank APIs
3. Allows role-based access control (PAYMENT_SUBMITTER vs AUDITOR vs ADMIN)
4. Does not require running Keycloak/Okta/Cognito locally for testing

## Decision

Implement **API Key authentication** via `X-API-Key` header with **role-based authorization** enforced by Spring Security's `SecurityFilterChain`.

### Roles

| Role | HTTP Access | Use Case |
|---|---|---|
| `ROLE_PAYMENT_SUBMITTER` | `POST /api/v1/payments` | Bank application servers, payment initiators |
| `ROLE_AUDITOR` | `GET /api/v1/payments/*`, `GET /api/v1/ledger/*` | Compliance, internal audit, reconciliation systems |
| `ROLE_ADMIN` | All endpoints including `/actuator/*` | Operations, monitoring systems |

### Keys (Development Defaults)

| Key | Role | Source |
|---|---|---|
| `nexor-pay-key-dev` | PAYMENT_SUBMITTER | `application.yml` / env var |
| `nexor-audit-key-dev` | AUDITOR | `application.yml` / env var |
| `nexor-admin-key-dev` | ADMIN | `application.yml` / env var |

Keys are overridden per environment via environment variables:
```
NEXOR_SECURITY_API_KEYS_PAYMENT_SUBMITTER=<secret>
NEXOR_SECURITY_API_KEYS_AUDITOR=<secret>
NEXOR_SECURITY_API_KEYS_ADMIN=<secret>
```

## Why API Key instead of JWT/OAuth2?

This is a deliberate architectural decoupling choice for a standalone reference service:

| Aspect | API Key (chosen) | JWT / OAuth2 |
|---|---|---|
| Local setup | Zero extra services | Requires Keycloak/Cognito |
| Pattern demonstrated | Filter → Auth → RBAC | Same structural pattern, more config |
| Production readiness | Partial | Full |
| Key rotation | Manual env var update | OIDC token expiry + refresh |

In a real bank deployment, JWT from the bank's IAM system would replace the API key. The `ApiKeyAuthenticationFilter` would be replaced by `JwtAuthenticationConverter` + `JwtDecoder`. The authorization rules in `SecurityFilterChain` would remain unchanged.

## Security Properties Implemented

1. **Authentication failure → HTTP 401** (not Spring Security's default 403 redirect)
2. **Stateless sessions** (`SessionCreationPolicy.STATELESS`) — no session fixation risk
3. **CSRF disabled** — correct for stateless API with header-based auth
4. **Security headers:**
   - `X-Frame-Options: DENY`
   - `X-Content-Type-Options: nosniff`
   - `Strict-Transport-Security` (1 year + includeSubDomains)
   - `Referrer-Policy: no-referrer`
   - `Permissions-Policy` (no geolocation/microphone/camera)

## Known Gaps (Honest Assessment)

1. **API keys are stored in plaintext** in env vars. Production: keys should be hashed with Argon2id and looked up via constant-time comparison.

2. **Key rotation requires restart.** Production: integrate with Vault or AWS Secrets Manager with live reload.

3. **No mutual TLS.** Bank-to-bank payments in production use mTLS at the transport layer. Not implemented here.

4. **No audit log of API key usage.** Production: every authenticated request should emit an audit event (who, when, what endpoint, from where).

5. **No IP allowlisting.** Production: payment-submitter keys should only be accepted from known IP ranges.

## Threat Model (Partial)

| Threat | Mitigation in place | Mitigation gap |
|---|---|---|
| Replay attack | Idempotency key required | No timestamp binding |
| Brute-force key enumeration | Rate limiting (100 req/min) | No lockout after N failures |
| Stolen key | Key per-role limits blast radius | No key rotation mechanism |
| DDoS | Rate limiting per key/IP | No WAF / load-balancer-level protection |
| CSRF | Not applicable (stateless header auth) | — |
| XSS | API returns JSON only | — |
