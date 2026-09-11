package com.nexor.payments.application.saga;

import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.domain.model.*;
import com.nexor.payments.infrastructure.adapter.out.clearing.MockCentralBankClearingRail;
import com.nexor.payments.infrastructure.adapter.out.clearing.PaymentReconciliationWorker;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryEventPublisher;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryIdempotencyStorage;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryLedgerRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Timeout != Failure & Reconciliation Engine Tests")
class TimeoutReconciliationTest {

    private PaymentSagaOrchestrator orchestrator;
    private InMemoryLedgerRepository ledgerRepository;
    private InMemoryPaymentRepository paymentRepository;
    private InMemoryEventPublisher eventPublisher;
    private MockCentralBankClearingRail clearingRail;
    private PaymentReconciliationWorker reconciliationWorker;

    private final AccountId debtor = AccountId.of("1001-9", "0001", "NEXOR");
    private final AccountId creditorTimeout = AccountId.of("8888-TIMEOUT", "0001", "OTHERBANK");

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        ledgerRepository = new InMemoryLedgerRepository();
        clearingRail = new MockCentralBankClearingRail();
        eventPublisher = new InMemoryEventPublisher();
        InMemoryIdempotencyStorage idempotencyStorage = new InMemoryIdempotencyStorage();
        FraudScreeningChain fraudChain = FraudScreeningChain.createDefault();
        SmartRailRouter router = new SmartRailRouter();

        orchestrator = new PaymentSagaOrchestrator(
                paymentRepository,
                ledgerRepository,
                clearingRail,
                eventPublisher,
                idempotencyStorage,
                fraudChain,
                router
        );

        reconciliationWorker = new PaymentReconciliationWorker(
                paymentRepository,
                ledgerRepository,
                clearingRail,
                eventPublisher
        );
    }

    @Test
    @DisplayName("Timeout != Failure: Should NOT prematurely compensate on timeout, but enter PENDING_INVESTIGATION")
    void shouldNotCompensateOnTimeout() {
        Money amount = Money.of("700.00", "BRL");
        String key = "IDEMP-" + UUID.randomUUID();

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                key, debtor, creditorTimeout, amount, "Payment under high latency", null);

        PaymentResponseDto response = orchestrator.submitPayment(command);

        // Core requirement: status must be PENDING_INVESTIGATION, NOT COMPENSATED!
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING_INVESTIGATION);
        assertThat(response.rejectionReason()).contains("state is UNKNOWN");

        // Outbox must have triggered an investigation request
        assertThat(eventPublisher.getOutbox())
                .anyMatch(event -> event.eventType().equals("PAYMENT_INVESTIGATION_REQUIRED"));

        // Now simulate the asynchronous Reconciliation Worker running an inquiry (pacs.028)
        PaymentInstruction pendingInstruction = paymentRepository.findById(new TransactionId(response.transactionId())).orElseThrow();
        reconciliationWorker.reconcilePendingInstruction(pendingInstruction);

        // After reconciliation, authoritative clearing confirmation safely settles the instruction
        PaymentInstruction reconciled = paymentRepository.findById(new TransactionId(response.transactionId())).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.SETTLED);

        assertThat(eventPublisher.getOutbox())
                .anyMatch(event -> event.eventType().equals("PAYMENT_RECONCILED_SETTLED"));
    }
}
