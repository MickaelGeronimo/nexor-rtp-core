package com.nexor.payments.application.port.out;

import com.nexor.payments.application.port.in.PaymentResponseDto;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyStoragePort {

    enum LockStatus {
        ACQUIRED,
        ALREADY_COMPLETED,
        CONFLICTING_PAYLOAD,
        CONCURRENT_EXECUTION
    }

    record AcquireResult(LockStatus status, PaymentResponseDto cachedResponse) {}

    AcquireResult tryAcquire(String idempotencyKey, String requestFingerprint);

    void markCompleted(String idempotencyKey, PaymentResponseDto response);

    void releaseLock(String idempotencyKey);
}
