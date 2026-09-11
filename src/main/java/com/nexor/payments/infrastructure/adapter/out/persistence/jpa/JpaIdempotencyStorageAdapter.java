package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.out.IdempotencyStoragePort;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.IdempotencyKeyJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Distributed idempotency storage adapter backed by a relational database.
 *
 * <p>Guarantees exactly-once execution across multiple JVM instances by leveraging
 * the PRIMARY KEY uniqueness constraint on {@code idempotency_keys}.
 *
 * <h2>Concurrency design</h2>
 * <ul>
 *   <li><b>tryAcquire()</b> is intentionally NOT {@code @Transactional}.  Each sub-call
 *       runs in its own auto-commit transaction.  This prevents a
 *       {@code DataIntegrityViolationException} from the INSERT attempt from poisoning
 *       any outer transaction context.</li>
 *   <li>The actual INSERT is delegated to {@link IdempotencyInsertHelper#tryInsertInFlight},
 *       which runs in a {@code REQUIRES_NEW} transaction.  Because it is a separate Spring
 *       bean, the AOP proxy correctly intercepts the annotation — direct {@code this.*()}
 *       calls would bypass the proxy and lose the transaction boundary.</li>
 *   <li>When two threads race, exactly one INSERT commits; the other gets
 *       {@code DataIntegrityViolationException} caught inside the helper, and that thread
 *       falls through to {@link #handleExistingKey} to await the winner's completion.</li>
 * </ul>
 */
@Primary
@Repository
public class JpaIdempotencyStorageAdapter implements IdempotencyStoragePort {

    private static final Logger log = LoggerFactory.getLogger(JpaIdempotencyStorageAdapter.class);

    private final SpringDataIdempotencyRepository repo;
    private final ObjectMapper objectMapper;
    private final IdempotencyInsertHelper insertHelper;

    @Autowired
    public JpaIdempotencyStorageAdapter(SpringDataIdempotencyRepository repo,
                                         ObjectMapper objectMapper,
                                         IdempotencyInsertHelper insertHelper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.insertHelper = insertHelper;
    }

    public JpaIdempotencyStorageAdapter(SpringDataIdempotencyRepository repo, ObjectMapper objectMapper) {
        this(repo, objectMapper, new IdempotencyInsertHelper(repo));
    }

    @Override
    public AcquireResult tryAcquire(String idempotencyKey, String requestFingerprint) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint cannot be null");

        // Fast-path: key already present — skip the INSERT attempt
        if (repo.existsById(idempotencyKey)) {
            return handleExistingKey(idempotencyKey, requestFingerprint);
        }

        // Attempt INSERT via the helper (REQUIRES_NEW transaction, fully isolated).
        // If two threads race here, only one INSERT commits; the loser returns false.
        boolean acquired = insertHelper.tryInsertInFlight(idempotencyKey, requestFingerprint);

        if (acquired) {
            log.debug("[DISTRIBUTED-IDEMP] Acquired distributed lock for key [{}]", idempotencyKey);
            return new AcquireResult(LockStatus.ACQUIRED, null);
        } else {
            log.debug("[DISTRIBUTED-IDEMP] Key [{}] collision — awaiting in-flight completion", idempotencyKey);
            return handleExistingKey(idempotencyKey, requestFingerprint);
        }
    }

    private AcquireResult handleExistingKey(String key, String fingerprint) {
        long deadline = System.currentTimeMillis() + 5_000;

        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotencyKeyJpaEntity> opt = repo.findById(key);

            if (opt.isEmpty()) {
                // Saga failed and released the lock → retry from scratch
                return tryAcquire(key, fingerprint);
            }

            IdempotencyKeyJpaEntity existing = opt.get();

            // Payload fingerprint check — prevent key reuse with a different request body
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                log.warn("[DISTRIBUTED-IDEMP] Payload conflict for key [{}]", key);
                return new AcquireResult(LockStatus.CONFLICTING_PAYLOAD, null);
            }

            if ("COMPLETED".equals(existing.getStatus())) {
                PaymentResponseDto cached = deserializeResponse(existing.getResponsePayload());
                log.info("[DISTRIBUTED-IDEMP] Returning cached COMPLETED response for key [{}]", key);
                return new AcquireResult(LockStatus.ALREADY_COMPLETED, cached);
            }

            // Still IN_FLIGHT — wait briefly and recheck
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
            }
        }

        log.warn("[DISTRIBUTED-IDEMP] Timed out waiting for key [{}] to complete", key);
        return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
    }

    @Override
    @Transactional
    public void markCompleted(String idempotencyKey, PaymentResponseDto response) {
        repo.findById(idempotencyKey).ifPresent(entity -> {
            try {
                entity.setStatus("COMPLETED");
                entity.setResponsePayload(objectMapper.writeValueAsString(response));
                repo.saveAndFlush(entity);
                log.debug("[DISTRIBUTED-IDEMP] Marked key [{}] as COMPLETED", idempotencyKey);
            } catch (Exception e) {
                log.error("Failed to serialize response for idempotency key [{}]", idempotencyKey, e);
            }
        });
    }

    @Override
    @Transactional
    public void releaseLock(String idempotencyKey) {
        repo.deleteById(idempotencyKey);
        log.debug("[DISTRIBUTED-IDEMP] Released distributed lock for key [{}]", idempotencyKey);
    }

    private PaymentResponseDto deserializeResponse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, PaymentResponseDto.class);
        } catch (Exception e) {
            log.error("Failed to deserialize cached idempotency response payload", e);
            return null;
        }
    }
}
