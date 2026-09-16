package com.nexor.payments.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window rate limiter, scoped per API key (falling back to client IP).
 *
 * <p><b>Algorithm:</b> Fixed window counter (simplest correct option).
 * Each key gets at most {@code requestsPerMinute} requests per 60-second window.
 * Window resets atomically when 60 seconds have elapsed.
 *
 * <p><b>Why not token bucket / leaky bucket?</b> Token bucket is strictly better
 * (smoother bursts), but requires a scheduled cleanup thread or an external store.
 * This fixed-window implementation is single-JVM, stateless across restarts,
 * and avoids pulling in Redis or Resilience4j for a portfolio project.
 * See ADR-007 for the full discussion.
 *
 * <p><b>Production gap:</b> This counter is per-JVM. In a multi-instance deployment,
 * a distributed counter (Redis INCR + EXPIRE) is needed. Documented in PRODUCTION_BLUEPRINT.md.
 *
 * <p><b>HTTP response on breach:</b> 429 Too Many Requests + Retry-After header.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final long WINDOW_MS = 60_000L;

    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitingFilter(int requestsPerMinute) {
        if (requestsPerMinute <= 0) throw new IllegalArgumentException("requestsPerMinute must be > 0");
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        long now = System.currentTimeMillis();
        evictExpiredCountersIfNecessary(now);

        String key = resolveKey(request);
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());

        if (!counter.tryIncrement(requestsPerMinute)) {
            long retryAfterSeconds = (WINDOW_MS - (now - counter.windowStart)) / 1000 + 1;
            log.warn("[RATE-LIMIT] Key={} exceeded {} req/min threshold", key, requestsPerMinute);
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
            response.getWriter().write(
                    "{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded. Retry after " +
                    retryAfterSeconds + " seconds.\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the rate-limit key: API Key header takes priority over IP address.
     * Using the API key means authenticated clients get their own bucket,
     * preventing a single client from consuming another's quota via IP sharing (NAT).
     */
    private String resolveKey(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void evictExpiredCountersIfNecessary(long now) {
        if (counters.size() > 5000) {
            counters.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
    }

    /**
     * Fixed-window counter — thread-safe via synchronized block on the counter instance.
     * We accept the synchronized cost here because rate-limit checks are rare hot paths
     * and the critical section is extremely small (two field reads + one increment).
     */
    static class WindowCounter {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);

        synchronized boolean tryIncrement(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= limit;
        }

        boolean isExpired(long now) {
            return (now - windowStart) > (2 * WINDOW_MS);
        }
    }
}
