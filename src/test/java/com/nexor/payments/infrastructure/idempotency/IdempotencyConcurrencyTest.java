package com.nexor.payments.infrastructure.idempotency;

import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.application.saga.PaymentSagaOrchestrator;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.domain.model.PaymentStatus;
import com.nexor.payments.infrastructure.adapter.out.clearing.MockCentralBankClearingRail;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryEventPublisher;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryIdempotencyStorage;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryLedgerRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Idempotency Multi-threaded Concurrency Tests")
class IdempotencyConcurrencyTest {

    @Test
    @DisplayName("High Concurrency: 20 simultaneous duplicate requests must debit ledger exactly once")
    void shouldPreventDoubleSpendingUnderConcurrentAssault() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        InMemoryPaymentRepository paymentRepository = new InMemoryPaymentRepository();
        InMemoryLedgerRepository ledgerRepository = new InMemoryLedgerRepository();
        MockCentralBankClearingRail clearingRail = new MockCentralBankClearingRail();
        InMemoryEventPublisher eventPublisher = new InMemoryEventPublisher();
        InMemoryIdempotencyStorage idempotencyStorage = new InMemoryIdempotencyStorage();
        FraudScreeningChain fraudChain = FraudScreeningChain.createDefault();
        SmartRailRouter router = new SmartRailRouter();

        PaymentSagaOrchestrator orchestrator = new PaymentSagaOrchestrator(
                paymentRepository,
                ledgerRepository,
                clearingRail,
                eventPublisher,
                idempotencyStorage,
                fraudChain,
                router
        );

        AccountId debtor = AccountId.of("1001-9", "0001", "NEXOR");
        AccountId creditor = AccountId.of("2002-8", "0001", "NEXOR");
        Money amount = Money.of("100.00", "BRL");
        String sharedIdempotencyKey = "IDEMP-BURST-TEST-999";

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                sharedIdempotencyKey, debtor, creditor, amount, "Concurrent burst test", null);

        List<PaymentResponseDto> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // wait for all threads to line up
                    PaymentResponseDto res = orchestrator.submitPayment(command);
                    responses.add(res);
                } catch (Exception ex) {
                    errors.add(ex);
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        // Check account balance: Initial 100,000.00 - 100.00 = exactly 99,900.00!
        // Never 98,000.00 (which would happen if double-spending occurred!)
        LedgerAccount debtorAcc = ledgerRepository.findAccountById(debtor).orElseThrow();
        assertThat(debtorAcc.getBalance()).isEqualTo(Money.of("99900.00", "BRL"));

        // At least one response succeeded; all successful responses have identical EndToEndId and TransactionId
        assertThat(responses).isNotEmpty();
        String expectedTxId = responses.get(0).transactionId();
        for (PaymentResponseDto dto : responses) {
            assertThat(dto.transactionId()).isEqualTo(expectedTxId);
            assertThat(dto.status()).isEqualTo(PaymentStatus.SETTLED);
        }
    }
}
