package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Isolated helper for the idempotency key INSERT operation.
 *
 * <p>Extracted into its own public Spring-managed bean so that
 * {@link Transactional#propagation() propagation = REQUIRES_NEW} is correctly
 * intercepted by the Spring AOP proxy. Calling a @Transactional(REQUIRES_NEW) method
 * on {@code this} inside the same bean bypasses the proxy — this helper solves that.
 *
 * <p>Uses native SQL {@code insertDirect} to enforce true database-level PRIMARY KEY
 * uniqueness. This prevents Hibernate's {@code em.merge()} behavior on assigned-ID entities,
 * guaranteeing that concurrent insertion races throw {@link DataIntegrityViolationException}.
 */
@Component
public class IdempotencyInsertHelper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInsertHelper.class);

    private final SpringDataIdempotencyRepository repo;

    public IdempotencyInsertHelper(SpringDataIdempotencyRepository repo) {
        this.repo = repo;
    }

    /**
     * Attempts to INSERT a new IN_FLIGHT idempotency record in its own isolated transaction.
     *
     * @param idempotencyKey    the idempotency key (PRIMARY KEY — uniqueness enforced by DB)
     * @param requestFingerprint SHA-256 of the request payload for conflict detection
     * @return true if INSERT succeeded (this thread acquired the lock),
     *         false if a concurrent thread already inserted the same key
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsertInFlight(String idempotencyKey, String requestFingerprint) {
        try {
            Instant now = Instant.now();
            Instant expiresAt = now.plus(24, ChronoUnit.HOURS);
            repo.insertDirect(idempotencyKey, requestFingerprint, "IN_FLIGHT", now, expiresAt);
            log.debug("[DISTRIBUTED-IDEMP] INSERT IN_FLIGHT succeeded for key [{}]", idempotencyKey);
            return true;
        } catch (DataIntegrityViolationException ex) {
            // PK collision: another thread inserted the same key between our existsById and our INSERT.
            // Only THIS inner REQUIRES_NEW transaction rolls back — the caller's context is unaffected.
            log.debug("[DISTRIBUTED-IDEMP] INSERT collision for key [{}] — another thread holds the lock", idempotencyKey);
            return false;
        }
    }
}
