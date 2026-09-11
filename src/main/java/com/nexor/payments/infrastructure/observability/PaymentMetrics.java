package com.nexor.payments.infrastructure.observability;

import com.nexor.payments.domain.model.PaymentRail;
import com.nexor.payments.domain.model.PaymentStatus;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business-level and technical metrics for Nexor RTP Core.
 *
 * <p><b>Metric families:</b>
 * <ul>
 *   <li>RED: Rate (transactions/sec), Error (compensations, fraud, failures), Duration (p50/p95/p99)</li>
 *   <li>Business: Fraud rejections by rule, Idempotency cache hits, Outbox pending backlog</li>
 *   <li>Infrastructure: Outbox relay latency, Kafka dispatch count</li>
 * </ul>
 *
 * <p><b>Why not just use @Timed and @Counted annotations?</b>
 * AOP-based annotations don't allow passing business-context tags (e.g. rail, fraud rule name)
 * at the point of measurement. Explicit instrumentation here enables richer dashboards.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer paymentProcessingTimer;
    private final AtomicLong outboxPendingBacklog;

    public PaymentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // RED: Duration — p50, p95, p99 percentiles with histogram for SLO tracking
        this.paymentProcessingTimer = Timer.builder("payment.orchestration.latency")
                .description("End-to-end latency of full payment orchestration saga (ms)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);

        // Business: Gauge for outbox pending events backlog (operational health signal)
        this.outboxPendingBacklog = new AtomicLong(0);
        Gauge.builder("payment.outbox.pending_backlog", outboxPendingBacklog, AtomicLong::get)
                .description("Number of outbox events in PENDING state (relay worker backlog)")
                .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Core payment metrics (RED: Rate + Error)
    // -------------------------------------------------------------------------

    public void recordPaymentProcessed(PaymentRail rail, PaymentStatus status) {
        Counter.builder("payment.transactions.total")
                .description("Total processed payment transactions by rail and terminal status")
                .tag("rail", rail != null ? rail.name() : "UNKNOWN")
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
    }

    public void recordCompensationTriggered(PaymentRail rail) {
        Counter.builder("payment.saga.compensations.total")
                .description("Total Saga compensating rollbacks triggered (authoritative clearing rejection)")
                .tag("rail", rail != null ? rail.name() : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    public void recordExecutionTime(Duration duration) {
        paymentProcessingTimer.record(duration);
    }

    // -------------------------------------------------------------------------
    // Fraud screening metrics — enables per-rule dashboards
    // -------------------------------------------------------------------------

    public void recordFraudRejection(String ruleName) {
        Counter.builder("payment.fraud.rejections.total")
                .description("Total payments rejected by fraud screening, by rule name")
                .tag("rule", ruleName != null ? ruleName : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Idempotency metrics — critical for detecting replay attacks vs normal retries
    // -------------------------------------------------------------------------

    /**
     * Records an idempotency cache hit — same key + same payload returned from cache.
     * High hit rate = clients are retrying correctly.
     * Unexpected hit rate = potential duplicate processing or client bug.
     */
    public void recordIdempotencyHit() {
        Counter.builder("payment.idempotency.cache_hits.total")
                .description("Total idempotency cache hits (duplicate request returned from cache)")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records an idempotency key conflict — same key, different payload (payload mismatch).
     * Should be zero in normal operation. Non-zero = client bug or replay attack.
     */
    public void recordIdempotencyConflict() {
        Counter.builder("payment.idempotency.conflicts.total")
                .description("Idempotency key reuse with different payload (potential replay attack)")
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Transactional outbox metrics
    // -------------------------------------------------------------------------

    public void recordOutboxEventPublished(String eventType) {
        Counter.builder("payment.outbox.events_published.total")
                .description("Total outbox events successfully dispatched to Kafka")
                .tag("event_type", eventType != null ? eventType : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    public void recordOutboxEventFailed(String eventType) {
        Counter.builder("payment.outbox.events_failed.total")
                .description("Total outbox events that failed dispatch and will be retried")
                .tag("event_type", eventType != null ? eventType : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    public void recordOutboxRelayLatency(Duration latency) {
        Timer.builder("payment.outbox.relay_latency")
                .description("Time between outbox event creation and Kafka dispatch (message delivery lag)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(latency);
    }

    /**
     * Updates the pending backlog gauge. Called by the outbox relay worker
     * after each poll cycle to report how many events are waiting.
     */
    public void updateOutboxPendingBacklog(long count) {
        outboxPendingBacklog.set(count);
    }
}
