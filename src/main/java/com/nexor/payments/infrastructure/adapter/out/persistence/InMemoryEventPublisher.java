package com.nexor.payments.infrastructure.adapter.out.persistence;

import com.nexor.payments.application.port.out.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);

    public record OutboxRecord(
            String id,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Instant occurredAt
    ) {}

    private final List<OutboxRecord> outbox = new CopyOnWriteArrayList<>();

    @Override
    public void publishOutboxEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        OutboxRecord record = new OutboxRecord(
                UUID.randomUUID().toString(),
                aggregateType,
                aggregateId,
                eventType,
                payload,
                Instant.now()
        );
        outbox.add(record);
        log.info("[OUTBOX] Emitted Event: {} for {} [{}] -> {}",
                eventType, aggregateType, aggregateId, payload);
    }

    public List<OutboxRecord> getOutbox() {
        return List.copyOf(outbox);
    }
}
