package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexor.payments.application.port.out.EventPublisherPort;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.OutboxEventJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Primary
@Component
public class JpaEventPublisherAdapter implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(JpaEventPublisherAdapter.class);

    private final SpringDataOutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public JpaEventPublisherAdapter(SpringDataOutboxRepository outboxRepo, ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void publishOutboxEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEventJpaEntity entity = new OutboxEventJpaEntity(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    payloadJson,
                    "PENDING",
                    0,
                    Instant.now()
            );

            outboxRepo.save(entity);
            log.info("[JPA-OUTBOX] Persisted Outbox Event: {} for {} [{}] -> DB Table 'outbox_events'",
                    eventType, aggregateType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event payload", e);
            throw new RuntimeException("Error persisting outbox event", e);
        }
    }
}
