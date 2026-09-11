package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private String payloadJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    public OutboxEventJpaEntity() {}

    public OutboxEventJpaEntity(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        this(java.util.UUID.randomUUID().toString(), aggregateType, aggregateId, eventType, payloadJson, "PENDING", 0, Instant.now());
    }

    public OutboxEventJpaEntity(String id, String aggregateType, String aggregateId, String eventType, String payloadJson, String status, int retryCount, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = status;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
    }

    public void claim(String workerId) {
        this.status = "PROCESSING";
        this.lockedBy = workerId;
        this.lockedAt = Instant.now();
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public void markFailed(Instant retryAt) {
        this.status = "FAILED";
        this.retryCount++;
        this.nextRetryAt = retryAt;
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public void markDeadLetter() {
        this.status = "DEAD_LETTER";
        this.retryCount++;
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public String getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(Instant lockedAt) { this.lockedAt = lockedAt; }
}
