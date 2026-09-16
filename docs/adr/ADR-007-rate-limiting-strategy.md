# ADR-007: Rate Limiting Strategy

**Status:** Accepted  
**Date:** 2026-09  
**Deciders:** Architecture Team

---

## Context

The payment API is a high-value target for:
1. **Abuse** — bots submitting fraudulent payments at scale
2. **Denial-of-Service** — overwhelming the clearing rail or database with concurrent requests
3. **Enumeration attacks** — probing valid account IDs by issuing many small transactions

We need a rate limiting mechanism that is simple, testable, and honest about its limitations.

## Decision

Implement a **fixed-window counter per API Key** (falling back to client IP) in `RateLimitingFilter`.

- **Window:** 60 seconds
- **Default limit:** 100 requests/minute per key
- **Override:** configurable via `nexor.security.rate-limit.requests-per-minute`
- **Scope:** per-JVM (single instance)
- **Response on breach:** HTTP 429 + `Retry-After` header

## Why Fixed Window (not Token Bucket)?

| Approach | Pros | Cons |
|---|---|---|
| **Fixed Window** | Trivially simple, no external deps, testable | Allows burst at window boundary (2x limit in worst case) |
| Token Bucket | Smoother traffic shaping, fairer | Requires background cleanup thread or external store |
| Sliding Window Log | Most accurate | High memory: O(n) per key |
| Redis + INCR/EXPIRE | Distributed, exact | External dependency, network latency, operational complexity |

For a self-contained reference core without an external Redis dependency, fixed window is the correct baseline. The "2x burst at boundary" edge case is acceptable here because:
- API keys are pre-authenticated
- The burst window (< 2s) is too short for meaningful financial abuse
- A multi-node production deployment would add Redis-backed distributed counters

## Trade-offs and Limitations

1. **Single-JVM scope:** In a multi-instance deployment, each JVM has its own counter. A client could get `N_instances × 100` requests/minute by load-balancing across instances. Documented in `PRODUCTION_BLUEPRINT.md` as a known gap requiring Redis `INCR + EXPIRE`.

2. **Memory Protection (Implemented):** `ConcurrentHashMap<String, WindowCounter>` includes `evictExpiredCountersIfNecessary(now)` triggering an eviction sweep of expired counters whenever the map exceeds 5,000 entries, preventing heap exhaustion under spoofed IP bursts.

3. **No DDoS protection:** This rate limiter is a fraud/abuse guard, not a DDoS mitigation tool. Production DDoS protection belongs at the load balancer/WAF layer (AWS Shield, Cloudflare).

## Consequences

- Added `RateLimitingFilter` registered before Spring Security's auth filter
- Configurable limit via `application.yml`
- 429 response includes `Retry-After` header for well-behaved clients
- `RateLimitingFilterTest` covers concurrent correctness with 200 virtual threads
