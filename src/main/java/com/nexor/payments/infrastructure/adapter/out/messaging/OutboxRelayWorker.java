package com.nexor.payments.infrastructure.adapter.out.messaging;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.OutboxEventJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox Relay Worker with Multi-Instance Concurrency Protection.
 *
 * <h2>Multi-Instance Concurrency & Claiming</h2>
 * <p>In multi-pod Kubernetes deployments, multiple relay instances poll concurrently.
 * Race conditions and duplicate publishing are prevented via:
 * <ul>
 *   <li><b>Pessimistic Locking with {@code SKIP LOCKED}</b>: {@link SpringDataOutboxRepository#findPendingOrRetryable(int)}
 *       executes {@code SELECT ... FOR UPDATE SKIP LOCKED}. Rows claimed by Pod A are skipped by Pod B
 *       without blocking, allowing horizontal scale without duplicate dispatch.</li>
 *   <li><b>Atomic Claim Lifecycle</b>: Rows transition from {@code PENDING} to {@code PROCESSING}
 *       tagged with {@code locked_by} (the instance/pod ID) and {@code locked_at}.</li>
 * </ul>
 *
 * <h2>At-Least-Once Delivery Semantics</h2>
 * <p>While {@code SKIP LOCKED} prevents concurrent workers from publishing the same event under normal
 * operation, the Transactional Outbox pattern fundamentally guarantees <b>at-least-once delivery</b>.
 * If a pod publishes an event to Kafka successfully, but crashes or loses its database connection before
 * the surrounding transaction commits the {@code PUBLISHED} status, the event remains {@code PENDING}
 * (or reverts on rollback) and will be republished on the next cycle. Therefore, <b>all downstream
 * event consumers MUST be idempotent</b>, utilizing transaction/event IDs for deduplication
 * (as demonstrated by Nexor's distributed idempotency layer).
 *
 * <h2>Retry & Dead Letter Queue (DLQ) Lifecycle</h2>
 * <ul>
 *   <li><b>PENDING</b>: Initial state upon atomic transaction commit.</li>
 *   <li><b>PROCESSING</b>: Claimed by a relay worker instance for publishing.</li>
 *   <li><b>PUBLISHED</b>: Successfully acknowledged by Kafka broker.</li>
 *   <li><b>FAILED</b>: Broker unavailable or send failed; {@code retry_count} incremented and
 *       {@code next_retry_at} set using exponential backoff ({@value #BASE_BACKOFF_SECONDS}s *
 *       2^attempt, capped at {@value #MAX_BACKOFF_SECONDS}s) so a degraded broker isn't hammered
 *       on every {@code fixedDelay} poll — retried again once {@code next_retry_at} has passed,
 *       up to {@value #MAX_RETRIES} attempts.</li>
 *   <li><b>DEAD_LETTER</b>: Exceeded {@value #MAX_RETRIES} attempts without ack.
 *       Quarantined for manual audit / alerts; avoids poison-pill head-of-line blocking.</li>
 * </ul>
 */
@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    public static final String PAYMENTS_TOPIC = "payments.events";
    public static final int MAX_RETRIES = 3;
    public static final long BASE_BACKOFF_SECONDS = 5;
    public static final long MAX_BACKOFF_SECONDS = 300;

    private final SpringDataOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String workerId;

    @Autowired
    public OutboxRelayWorker(
            SpringDataOutboxRepository outboxRepository,
            @Nullable KafkaTemplate<String, String> kafkaTemplate) {
        this(outboxRepository, kafkaTemplate, generateDefaultWorkerId());
    }

    public OutboxRelayWorker(
            SpringDataOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            String workerId) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.workerId = workerId;
    }

    private static String generateDefaultWorkerId() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        return "pod-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String getWorkerId() {
        return workerId;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void pollAndPublishPendingEvents() {
        // SELECT ... FOR UPDATE SKIP LOCKED ensures concurrent pods skip each other's claimed rows
        List<OutboxEventJpaEntity> pendingEvents = outboxRepository.findPendingOrRetryable(MAX_RETRIES);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("[OUTBOX-RELAY:{}] Claimed {} pending/retryable events to relay to topic '{}'",
                workerId, pendingEvents.size(), PAYMENTS_TOPIC);

        for (OutboxEventJpaEntity event : pendingEvents) {
            // Atomic claim: set in-flight PROCESSING state with worker metadata
            event.claim(workerId);

            try {
                // Dispatch directly to Kafka topic using Spring KafkaTemplate
                dispatchToKafka(event.getAggregateId(), event.getEventType(), event.getPayloadJson());

                event.markPublished();
                outboxRepository.save(event);

                log.info("[OUTBOX-RELAY:{}] Event [{}:{}] successfully relayed to Kafka topic '{}'",
                        workerId, event.getEventType(), event.getId(), PAYMENTS_TOPIC);
            } catch (Exception ex) {
                int nextRetry = event.getRetryCount() + 1;

                if (nextRetry >= MAX_RETRIES) {
                    event.markDeadLetter();
                    log.error("[OUTBOX-RELAY:{}] Event [{}] exceeded max retries ({}/{}). Moved to DEAD_LETTER.",
                            workerId, event.getId(), nextRetry, MAX_RETRIES, ex);
                } else {
                    Duration backoff = backoffFor(nextRetry);
                    event.markFailed(Instant.now().plus(backoff));
                    log.warn("[OUTBOX-RELAY:{}] Failed to relay event [{}] (attempt {}/{}). Backing off {}s before next attempt.",
                            workerId, event.getId(), nextRetry, MAX_RETRIES, backoff.toSeconds());
                }
                outboxRepository.save(event);
            }
        }
    }

    /**
     * Exponential backoff: {@link #BASE_BACKOFF_SECONDS} * 2^attempt, capped at
     * {@link #MAX_BACKOFF_SECONDS}. Without this, a degraded/unreachable broker got hammered on
     * every {@code fixedDelay=2000} poll cycle regardless of how recently an event last failed —
     * the query only checked {@code retry_count < MAX_RETRIES}, with no time-based gate at all.
     */
    private static Duration backoffFor(int attempt) {
        long seconds = Math.min(MAX_BACKOFF_SECONDS, BASE_BACKOFF_SECONDS * (1L << Math.min(attempt, 10)));
        return Duration.ofSeconds(seconds);
    }

    public void dispatchToKafka(String aggregateId, String eventType, String payloadJson) {
        if (kafkaTemplate != null) {
            try {
                log.debug("[KAFKA-PRODUCER] Dispatching via KafkaTemplate to topic '{}' with key '{}'", PAYMENTS_TOPIC, aggregateId);
                // Synchronous get with timeout to ensure broker acknowledged before marking PUBLISHED
                kafkaTemplate.send(PAYMENTS_TOPIC, aggregateId, payloadJson).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.error("[KAFKA-PRODUCER] Failed to deliver event to Kafka broker for key '{}'", aggregateId, ex);
                throw new RuntimeException("Kafka dispatch delivery failure for key: " + aggregateId, ex);
            }
        } else {
            log.info("[KAFKA-PRODUCER] KafkaTemplate simulated send (stand-alone test profile): Topic='{}', Key='{}', Event='{}'",
                    PAYMENTS_TOPIC, aggregateId, eventType);
        }
    }
}
