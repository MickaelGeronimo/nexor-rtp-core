package com.nexor.payments.infrastructure.idempotency;

import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.port.in.SubmitPaymentUseCase;
import com.nexor.payments.application.port.out.*;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.application.saga.PaymentSagaOrchestrator;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.domain.model.PaymentRail;
import com.nexor.payments.domain.model.PaymentStatus;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataJournalEntryRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataOutboxRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulated Multi-Instance Distributed Idempotency Stress Test.
 *
 * <p>Simulates 3 application instances (Pods A, B, and C) within the same JVM process
 * by instantiating 3 separate {@link SubmitPaymentUseCase} orchestrators sharing the exact
 * same database repository beans.
 *
 * <p><b>Test Scope:</b> Verifies that relational PRIMARY KEY locks and atomic row transitions
 * protect the database against concurrent races across instances, guaranteeing exactly-once
 * execution without requiring a live multi-pod Kubernetes cluster.
 */
@SpringBootTest
@DisplayName("Simulated Multi-Instance Distributed Idempotency Stress Test (In-Process Concurrency)")
class MultiInstanceDistributedIdempotencyStressTest extends com.nexor.payments.testsupport.AbstractContainerizedTest {

    private static final Logger log = LoggerFactory.getLogger(MultiInstanceDistributedIdempotencyStressTest.class);

    @Autowired
    private PaymentRepositoryPort paymentRepository;

    @Autowired
    private LedgerRepositoryPort ledgerRepository;

    @Autowired
    private ClearingRailPort clearingRailPort;

    @Autowired
    private EventPublisherPort eventPublisher;

    @Autowired
    private IdempotencyStoragePort idempotencyStorage;

    @Autowired
    private PaymentTransactionCoordinatorPort transactionCoordinator;

    @Autowired
    private SpringDataPaymentRepository paymentRepo;

    @Autowired
    private SpringDataJournalEntryRepository journalRepo;

    @Autowired
    private SpringDataOutboxRepository outboxRepo;

    @Test
    @DisplayName("100 concurrent requests across 3 simulated application instances sharing database: Exactly 1 payment, 1 debit, 1 journal entry, 2 outbox events (FUNDS_RESERVED + SETTLED_CLEARING)")
    void shouldHandle100ConcurrentRequestsAcross3InstancesWithSingleDebit() throws Exception {
        FraudScreeningChain fraudChain = new FraudScreeningChain(List.of(
                new com.nexor.payments.application.fraud.SanctionsScreeningRule(),
                new com.nexor.payments.application.fraud.HighValueScreeningRule(),
                new com.nexor.payments.application.fraud.VelocityCheckRule(10_000)
        ));
        SmartRailRouter router = new SmartRailRouter();

        SubmitPaymentUseCase instanceA = new PaymentSagaOrchestrator(
                paymentRepository, ledgerRepository, clearingRailPort, eventPublisher, idempotencyStorage, fraudChain, router, transactionCoordinator);
        SubmitPaymentUseCase instanceB = new PaymentSagaOrchestrator(
                paymentRepository, ledgerRepository, clearingRailPort, eventPublisher, idempotencyStorage, fraudChain, router, transactionCoordinator);
        SubmitPaymentUseCase instanceC = new PaymentSagaOrchestrator(
                paymentRepository, ledgerRepository, clearingRailPort, eventPublisher, idempotencyStorage, fraudChain, router, transactionCoordinator);

        List<SubmitPaymentUseCase> instances = List.of(instanceA, instanceB, instanceC);

        AccountId debtorId = AccountId.of("1001-9", "0001", "NEXOR");
        AccountId creditorId = AccountId.of("8888-2", "0001", "OTHERBANK");

        Money initialDebtorBalance = ledgerRepository.findAccountById(debtorId).orElseThrow().getBalance();
        long initialJournalCount = journalRepo.count();
        long initialPaymentCount = paymentRepo.count();
        long initialOutboxCount = outboxRepo.count();

        int totalRequests = 100;
        String sharedIdempotencyKey = "MULTI-POD-IDEMP-" + System.currentTimeMillis();
        Money paymentAmount = Money.of("1500.00", "BRL");

        ExecutorService executor = Executors.newFixedThreadPool(25);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(totalRequests);

        List<PaymentResponseDto> successfulResponses = new CopyOnWriteArrayList<>();
        AtomicInteger lockContentionRetries = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            final SubmitPaymentUseCase targetInstance = instances.get(i % instances.size());

            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize assault start

                    SubmitPaymentCommand command = new SubmitPaymentCommand(
                            sharedIdempotencyKey,
                            debtorId,
                            creditorId,
                            paymentAmount,
                            "Multi-instance stress test",
                            "PIX"
                    );

                    // Client retry loop on transient lock contention (simulating standard banking client retry)
                    PaymentResponseDto response = null;
                    int attempts = 0;
                    while (response == null && attempts < 100) {
                        try {
                            attempts++;
                            response = targetInstance.submitPayment(command);
                        } catch (Exception e) {
                            // Transient concurrent execution lock or DB contention from sibling pod
                            lockContentionRetries.incrementAndGet();
                            Thread.sleep(30);
                        }
                    }

                    if (response != null) {
                        successfulResponses.add(response);
                    }
                } catch (Exception ex) {
                    log.error("Worker {} encountered unexpected error", requestId, ex);
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown(); // Open the gate: fire all 100 requests simultaneously!
        boolean completed = finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        log.info("[MULTI-INSTANCE-TEST] Completed {} requests with {} transient contention retries",
                successfulResponses.size(), lockContentionRetries.get());

        // Assert 1: All 100 requests succeeded and obtained an authoritative response
        assertThat(successfulResponses).hasSize(totalRequests);

        // Assert 2: Every response returned the exact same Transaction ID and EndToEndId
        String canonicalTxId = successfulResponses.get(0).transactionId();
        String canonicalEndToEndId = successfulResponses.get(0).endToEndId();
        for (PaymentResponseDto response : successfulResponses) {
            assertThat(response.transactionId()).isEqualTo(canonicalTxId);
            assertThat(response.endToEndId()).isEqualTo(canonicalEndToEndId);
            assertThat(response.status()).isEqualTo(PaymentStatus.SETTLED);
        }

        // Assert 3: Exactly 1 payment instruction was persisted in the shared database
        long paymentsAdded = paymentRepo.count() - initialPaymentCount;
        assertThat(paymentsAdded).isEqualTo(1);

        // Assert 4: Exactly 1 debit occurred in the debtor account balance
        Money finalDebtorBalance = ledgerRepository.findAccountById(debtorId).orElseThrow().getBalance();
        assertThat(finalDebtorBalance).isEqualTo(initialDebtorBalance.subtract(paymentAmount));

        // Assert 5: Exactly 1 JournalEntry was recorded in the immutable ledger
        long journalsAdded = journalRepo.count() - initialJournalCount;
        assertThat(journalsAdded).isEqualTo(1);

        // Assert 6: Exactly 2 Outbox events were persisted (FUNDS_RESERVED + SETTLED_CLEARING)
        long outboxAdded = outboxRepo.count() - initialOutboxCount;
        assertThat(outboxAdded).isEqualTo(2);
    }
}
