package com.nexor.payments.infrastructure.idempotency;

import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.out.IdempotencyStoragePort;
import com.nexor.payments.domain.model.PaymentRail;
import com.nexor.payments.domain.model.PaymentStatus;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.JpaIdempotencyStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("Database-Backed Distributed Idempotency Tests (real PostgreSQL via Testcontainers)")
class DistributedDatabaseIdempotencyTest extends com.nexor.payments.testsupport.AbstractContainerizedTest {

    @Autowired
    private JpaIdempotencyStorageAdapter adapter;

    @Test
    @DisplayName("Should acquire distributed DB lock, persist completion, and return cached response on replay")
    void shouldHandleDatabaseIdempotencyLifecycle() {

        String key = "DIST-IDEMP-" + UUID.randomUUID();
        String fingerprint = "sha256-fingerprint-abc-123";

        // Step 1: Initial Acquisition
        var result1 = adapter.tryAcquire(key, fingerprint);
        assertThat(result1.status()).isEqualTo(IdempotencyStoragePort.LockStatus.ACQUIRED);

        // Step 2: Mark Completed with response payload in database
        PaymentResponseDto responseDto = new PaymentResponseDto(
                "TX-100",
                "E202609080001",
                "DEBTOR-1",
                "CREDITOR-2",
                "50.00",
                "BRL",
                PaymentRail.PIX,
                PaymentStatus.SETTLED,
                "BACEN-CLEARING-REF",
                null,
                Instant.now()
        );
        adapter.markCompleted(key, responseDto);

        // Step 3: Replay from simulated Instance B with identical key & fingerprint
        var result2 = adapter.tryAcquire(key, fingerprint);
        assertThat(result2.status()).isEqualTo(IdempotencyStoragePort.LockStatus.ALREADY_COMPLETED);
        assertThat(result2.cachedResponse()).isNotNull();
        assertThat(result2.cachedResponse().transactionId()).isEqualTo("TX-100");
        assertThat(result2.cachedResponse().status()).isEqualTo(PaymentStatus.SETTLED);

        // Step 4: Replay with TAMPERED / conflicting payload
        var resultConflict = adapter.tryAcquire(key, "altered-tampered-fingerprint-999");
        assertThat(resultConflict.status()).isEqualTo(IdempotencyStoragePort.LockStatus.CONFLICTING_PAYLOAD);
    }
}
