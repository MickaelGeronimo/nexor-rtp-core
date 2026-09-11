package com.nexor.payments.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RateLimitingFilter.WindowCounter.
 *
 * <p>These tests verify the thread-safety and correctness of the rate-limiting
 * algorithm under concurrent load — without needing a servlet container.
 */
@DisplayName("Rate Limiting Algorithm Tests")
class RateLimitingFilterTest {

    private RateLimitingFilter.WindowCounter counter;

    @BeforeEach
    void setUp() {
        counter = new RateLimitingFilter.WindowCounter();
    }

    @Test
    @DisplayName("Sequential requests: should allow up to limit and block afterwards")
    void shouldAllowUpToLimitAndBlockAfterwards() {
        int limit = 5;

        for (int i = 0; i < limit; i++) {
            boolean allowed = counter.tryIncrement(limit);
            assertThat(allowed)
                    .as("Request %d should be allowed", i + 1)
                    .isTrue();
        }

        // Next request must be blocked
        boolean overLimit = counter.tryIncrement(limit);
        assertThat(overLimit)
                .as("Request %d should be blocked (over limit)", limit + 1)
                .isFalse();
    }

    @Test
    @DisplayName("Concurrent requests: exactly 'limit' allowed, rest rejected — no race conditions")
    void shouldHandleConcurrentRequestsWithoutRaceConditions() throws InterruptedException {
        int limit = 50;
        int totalThreads = 200;

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        // Use fixed thread pool (Java 17 compatible — no virtual threads required)
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        try {
            for (int i = 0; i < totalThreads; i++) {
                pool.submit(() -> {
                    try {
                        startLatch.await(); // All threads start simultaneously
                        if (counter.tryIncrement(limit)) {
                            allowed.incrementAndGet();
                        } else {
                            blocked.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Release all threads
            doneLatch.await(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Exactly 'limit' requests should have been allowed — no over-counting, no under-counting
        assertThat(allowed.get())
                .as("Exactly %d requests should be allowed", limit)
                .isEqualTo(limit);
        assertThat(blocked.get())
                .as("Remaining %d requests should be blocked", totalThreads - limit)
                .isEqualTo(totalThreads - limit);
    }

    @Test
    @DisplayName("API key filter: constructed correctly with non-null keys")
    void shouldConstructApiKeyFilterWithoutErrors() {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                "pay-key", "audit-key", "admin-key");

        assertThat(filter).isNotNull();
    }

    @Test
    @DisplayName("Rate limiter: limit of 1 allows exactly 1 request then blocks")
    void shouldBlockAtLimitOfOne() {
        RateLimitingFilter.WindowCounter strictCounter = new RateLimitingFilter.WindowCounter();

        assertThat(strictCounter.tryIncrement(1)).isTrue();
        assertThat(strictCounter.tryIncrement(1)).isFalse();
        assertThat(strictCounter.tryIncrement(1)).isFalse();
    }
}
