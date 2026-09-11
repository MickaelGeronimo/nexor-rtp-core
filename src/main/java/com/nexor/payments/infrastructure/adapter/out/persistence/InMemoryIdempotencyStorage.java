package com.nexor.payments.infrastructure.adapter.out.persistence;

import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.out.IdempotencyStoragePort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * High-concurrency in-memory idempotency storage engine.
 * Emulates Redis distributed locks with non-blocking coordination:
 * Concurrent duplicate requests automatically block and await completion of the in-flight
 * execution, returning the exact cached response once settled, completely preventing double-spend.
 */
@Repository
public class InMemoryIdempotencyStorage implements IdempotencyStoragePort {

    private record StoredEntry(
            String fingerprint,
            CompletableFuture<PaymentResponseDto> future,
            Instant createdAt
    ) {}

    private final Map<String, StoredEntry> store = new ConcurrentHashMap<>();

    @Override
    public AcquireResult tryAcquire(String idempotencyKey, String requestFingerprint) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint cannot be null");

        while (true) {
            StoredEntry existing = store.get(idempotencyKey);

            if (existing == null) {
                StoredEntry newEntry = new StoredEntry(requestFingerprint, new CompletableFuture<>(), Instant.now());
                if (store.putIfAbsent(idempotencyKey, newEntry) == null) {
                    return new AcquireResult(LockStatus.ACQUIRED, null);
                }
                continue;
            }

            // Fingerprint check: Prevents key reuse with different payload
            if (!existing.fingerprint().equals(requestFingerprint)) {
                return new AcquireResult(LockStatus.CONFLICTING_PAYLOAD, null);
            }

            // If in-flight, await completion of the primary execution
            try {
                PaymentResponseDto completedResponse = existing.future().get(10, TimeUnit.SECONDS);
                return new AcquireResult(LockStatus.ALREADY_COMPLETED, completedResponse);
            } catch (TimeoutException e) {
                return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
            } catch (ExecutionException e) {
                // The in-flight execution failed; remove stale entry and loop
                store.remove(idempotencyKey, existing);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
            }
        }
    }

    @Override
    public void markCompleted(String idempotencyKey, PaymentResponseDto response) {
        StoredEntry entry = store.get(idempotencyKey);
        if (entry != null) {
            entry.future().complete(response);
        }
    }

    @Override
    public void releaseLock(String idempotencyKey) {
        StoredEntry entry = store.remove(idempotencyKey);
        if (entry != null) {
            entry.future().completeExceptionally(new RuntimeException("Lock released due to transaction failure"));
        }
    }
}
