package com.nexor.payments.infrastructure.messaging;

import com.nexor.payments.infrastructure.adapter.out.messaging.OutboxRelayWorker;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.OutboxEventJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Outbox Multi-Instance Concurrency & Claiming Tests")
class OutboxMultiInstanceConcurrencyTest {

    @Test
    @DisplayName("Multiple concurrent pods/workers must process pending events with ZERO duplicate Kafka dispatches")
    void shouldProcessEventsWithoutDuplicateDispatchesAcrossConcurrentWorkers() throws Exception {
        int totalEvents = 50;
        int workerCount = 5;

        // Shared thread-safe storage mimicking database table with row-level locks
        Map<String, OutboxEventJpaEntity> table = new ConcurrentHashMap<>();
        Set<String> lockedRowIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CopyOnWriteArrayList<String> publishedEventKeys = new CopyOnWriteArrayList<>();
        AtomicInteger totalKafkaDispatches = new AtomicInteger(0);

        for (int i = 1; i <= totalEvents; i++) {
            String id = "EVT-" + i;
            OutboxEventJpaEntity entity = new OutboxEventJpaEntity(
                    id,
                    "Payment",
                    "PAY-" + i,
                    "PAYMENT_SETTLED_CLEARING",
                    "{\"amount\": 100.00}",
                    "PENDING",
                    0,
                    Instant.now().minusSeconds(totalEvents - i)
            );
            table.put(id, entity);
        }

        // Stub repository that accurately simulates SELECT ... FOR UPDATE SKIP LOCKED
        SpringDataOutboxRepository repository = mock(SpringDataOutboxRepository.class);
        when(repository.findPendingOrRetryable(anyInt())).thenAnswer(invocation -> {
            List<OutboxEventJpaEntity> claimedForCaller = new ArrayList<>();
            synchronized (table) {
                for (OutboxEventJpaEntity entity : table.values()) {
                    if ("PENDING".equals(entity.getStatus()) && !lockedRowIds.contains(entity.getId())) {
                        lockedRowIds.add(entity.getId());
                        claimedForCaller.add(entity);
                        if (claimedForCaller.size() >= 10) {
                            break; // batch size limit
                        }
                    }
                }
            }
            return claimedForCaller;
        });

        when(repository.save(any(OutboxEventJpaEntity.class))).thenAnswer(invocation -> {
            OutboxEventJpaEntity saved = invocation.getArgument(0);
            table.put(saved.getId(), saved);
            if ("PUBLISHED".equals(saved.getStatus())) {
                lockedRowIds.remove(saved.getId()); // lock released upon transaction commit
            }
            return saved;
        });

        // Kafka template tracking dispatches
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(), any(), any())).thenAnswer(invocation -> {
            String aggregateKey = invocation.getArgument(1);
            totalKafkaDispatches.incrementAndGet();
            publishedEventKeys.add(aggregateKey);
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        // Spin up multiple worker instances simulating Pod A, Pod B, Pod C, Pod D, Pod E
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(workerCount);

        for (int w = 1; w <= workerCount; w++) {
            String podId = "pod-nexor-core-" + w;
            OutboxRelayWorker worker = new OutboxRelayWorker(repository, kafkaTemplate, podId);
            executor.submit(() -> {
                try {
                    startGate.await(); // ensure all workers race simultaneously
                    // Run poll cycles until all events in this batch are exhausted
                    for (int cycle = 0; cycle < 10; cycle++) {
                        worker.pollAndPublishPendingEvents();
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startGate.countDown(); // Unleash the race
        boolean finished = completionLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        // INVARIANT 1: Zero duplicate dispatches to Kafka
        assertThat(totalKafkaDispatches.get())
                .as("Total Kafka dispatches must exactly equal the number of events (no duplicates)")
                .isEqualTo(totalEvents);

        // INVARIANT 2: Every single event key was published exactly once
        Set<String> uniqueKeys = new HashSet<>(publishedEventKeys);
        assertThat(uniqueKeys.size())
                .as("Every event must be published exactly once")
                .isEqualTo(totalEvents);

        // INVARIANT 3: All rows in the database reached PUBLISHED state
        long publishedCount = table.values().stream()
                .filter(e -> "PUBLISHED".equals(e.getStatus()))
                .count();
        assertThat(publishedCount)
                .as("All rows must reach PUBLISHED status")
                .isEqualTo(totalEvents);
    }
}
