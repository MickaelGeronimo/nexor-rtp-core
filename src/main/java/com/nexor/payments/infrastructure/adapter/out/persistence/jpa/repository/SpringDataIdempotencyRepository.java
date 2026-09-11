package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.IdempotencyKeyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SpringDataIdempotencyRepository extends JpaRepository<IdempotencyKeyJpaEntity, String> {

    /**
     * Direct native SQL INSERT to enforce database-level PRIMARY KEY constraint.
     * Bypasses Hibernate merge() semantics on assigned IDs, guaranteeing a true
     * DataIntegrityViolationException on concurrent insert race conditions.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at, expires_at) VALUES (:key, :fingerprint, :status, :createdAt, :expiresAt)", nativeQuery = true)
    int insertDirect(
            @Param("key") String key,
            @Param("fingerprint") String fingerprint,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("expiresAt") Instant expiresAt
    );
}
