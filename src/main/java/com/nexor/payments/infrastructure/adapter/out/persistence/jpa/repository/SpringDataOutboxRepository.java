package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.OutboxEventJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, String> {

    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Polls pending or retryable failed events using pessimistic row locking with
     * {@code SKIP LOCKED} (timeout = -2). Prevents multiple concurrent pods/threads
     * from acquiring the same events simultaneously.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = 'PENDING' OR (e.status = 'FAILED' AND e.retryCount < :maxRetries AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= CURRENT_TIMESTAMP)) ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findPendingOrRetryable(@Param("maxRetries") int maxRetries);

    /**
     * Chunked batch query with {@code SKIP LOCKED} and stale-lock reclamation.
     * Also reclaims events stuck in 'PROCESSING' whose worker died or timed out (older than staleThreshold).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = 'PENDING' OR (e.status = 'FAILED' AND e.retryCount < :maxRetries AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= CURRENT_TIMESTAMP)) OR (e.status = 'PROCESSING' AND e.lockedAt IS NOT NULL AND e.lockedAt < :staleThreshold) ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findPendingOrRetryableWithLock(
            @Param("maxRetries") int maxRetries,
            @Param("staleThreshold") Instant staleThreshold,
            Pageable pageable);
}
