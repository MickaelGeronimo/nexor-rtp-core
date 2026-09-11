package com.nexor.payments.infrastructure.messaging;

import com.nexor.payments.infrastructure.adapter.out.messaging.OutboxRelayWorker;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.OutboxEventJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox Relay Worker & Kafka Producer Tests")
class OutboxRelayWorkerTest {

    @Mock
    private SpringDataOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Should successfully publish pending events to Kafka topic and mark as PUBLISHED")
    void shouldRelayPendingEventsToKafka() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "Payment",
                "TX-1001",
                "PAYMENT_SETTLED_CLEARING",
                "{\"endToEndId\":\"E123\"}"
        );

        when(outboxRepository.findPendingOrRetryable(anyInt()))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("payments.events"), eq("TX-1001"), eq(event.getPayloadJson())))
                .thenReturn(future);

        OutboxRelayWorker worker = new OutboxRelayWorker(outboxRepository, kafkaTemplate);
        worker.pollAndPublishPendingEvents();

        verify(kafkaTemplate, times(1)).send("payments.events", "TX-1001", event.getPayloadJson());
        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Should mark event as FAILED and increment retry_count on transient Kafka broker rejection")
    void shouldMarkFailedWhenKafkaRejects() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "Payment",
                "TX-9999",
                "PAYMENT_SETTLED_CLEARING",
                "{\"endToEndId\":\"E999\"}"
        );

        when(outboxRepository.findPendingOrRetryable(anyInt()))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka Broker Unavailable"));
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(failedFuture);

        OutboxRelayWorker worker = new OutboxRelayWorker(outboxRepository, kafkaTemplate);
        worker.pollAndPublishPendingEvents();

        assertThat(event.getStatus()).isEqualTo("FAILED");
        assertThat(event.getRetryCount()).isEqualTo(1);
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Should set an exponentially increasing next_retry_at on each successive failure instead of retrying every fixed 2s poll")
    void shouldApplyExponentialBackoffOnRepeatedFailures() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "Payment",
                "TX-BACKOFF",
                "PAYMENT_SETTLED_CLEARING",
                "{\"endToEndId\":\"E-BACKOFF\"}"
        );

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka Broker Unavailable"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failedFuture);

        OutboxRelayWorker worker = new OutboxRelayWorker(outboxRepository, kafkaTemplate);

        // Attempt 1
        when(outboxRepository.findPendingOrRetryable(anyInt())).thenReturn(List.of(event));
        worker.pollAndPublishPendingEvents();
        assertThat(event.getStatus()).isEqualTo("FAILED");
        java.time.Instant firstBackoffUntil = event.getNextRetryAt();
        assertThat(firstBackoffUntil).isNotNull().isAfter(java.time.Instant.now());

        // Attempt 2: backoff window must grow, not repeat the same short fixed delay
        worker.pollAndPublishPendingEvents();
        assertThat(event.getStatus()).isEqualTo("FAILED");
        java.time.Instant secondBackoffUntil = event.getNextRetryAt();
        assertThat(secondBackoffUntil).isAfter(firstBackoffUntil);
    }

    @Test
    @DisplayName("Should transition event to DEAD_LETTER when maxRetries threshold is reached")
    void shouldTransitionToDeadLetterWhenMaxRetriesReached() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "Payment",
                "TX-POISON",
                "PAYMENT_SETTLED_CLEARING",
                "{\"endToEndId\":\"E-POISON\"}"
        );
        event.setRetryCount(OutboxRelayWorker.MAX_RETRIES - 1); // e.g. attempt 2 of 3

        when(outboxRepository.findPendingOrRetryable(anyInt()))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Persistent Kafka Broker Outage"));
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(failedFuture);

        OutboxRelayWorker worker = new OutboxRelayWorker(outboxRepository, kafkaTemplate);
        worker.pollAndPublishPendingEvents();

        assertThat(event.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(event.getRetryCount()).isEqualTo(OutboxRelayWorker.MAX_RETRIES);
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Should stamp workerId during claim lifecycle and clear lock fields on successful publish")
    void shouldStampWorkerIdDuringClaimLifecycle() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "Payment",
                "TX-POD-1",
                "PAYMENT_SETTLED_CLEARING",
                "{\"endToEndId\":\"E-POD\"}"
        );

        when(outboxRepository.findPendingOrRetryable(anyInt()))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);

        OutboxRelayWorker worker = new OutboxRelayWorker(outboxRepository, kafkaTemplate, "pod-worker-alpha");
        assertThat(worker.getWorkerId()).isEqualTo("pod-worker-alpha");

        worker.pollAndPublishPendingEvents();

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isNotNull();
        // Upon publish completion, lock fields are cleared
        assertThat(event.getLockedBy()).isNull();
        assertThat(event.getLockedAt()).isNull();
    }
}
