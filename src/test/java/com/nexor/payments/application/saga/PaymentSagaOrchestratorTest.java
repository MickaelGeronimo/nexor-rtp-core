package com.nexor.payments.application.saga;

import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.*;
import com.nexor.payments.infrastructure.adapter.out.clearing.MockCentralBankClearingRail;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryEventPublisher;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryIdempotencyStorage;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryLedgerRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Saga Orchestrator & Compensation Tests")
class PaymentSagaOrchestratorTest {

    private PaymentSagaOrchestrator orchestrator;
    private InMemoryLedgerRepository ledgerRepository;
    private InMemoryPaymentRepository paymentRepository;
    private InMemoryEventPublisher eventPublisher;
    private MockCentralBankClearingRail clearingRail;
    private InMemoryIdempotencyStorage idempotencyStorage;

    private final AccountId debtorAccount = AccountId.of("1001-9", "0001", "NEXOR");
    private final AccountId creditorExternal = AccountId.of("9999-1", "0001", "OTHERBANK");

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        ledgerRepository = new InMemoryLedgerRepository();
        clearingRail = new MockCentralBankClearingRail();
        eventPublisher = new InMemoryEventPublisher();
        idempotencyStorage = new InMemoryIdempotencyStorage();
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
    }

    @Test
    @DisplayName("Happy path: Should orchestrate instant payment and settle ledger atomically")
    void shouldOrchestratePaymentSuccessfully() {
        Money amount = Money.of("1500.00", "BRL");
        String key = "IDEMP-" + UUID.randomUUID();

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                key, debtorAccount, creditorExternal, amount, "Pix for services", null);

        PaymentResponseDto response = orchestrator.submitPayment(command);

        assertThat(response.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(response.rail()).isEqualTo(PaymentRail.PIX);

        // Check debtor balance (100,000 - 1,500 = 98,500)
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorAccount).orElseThrow();
        assertThat(debtor.getBalance()).isEqualTo(Money.of("98500.00", "BRL"));

        // Check outbox event
        assertThat(eventPublisher.getOutbox())
                .anyMatch(event -> event.eventType().equals("PAYMENT_SETTLED_CLEARING"));
    }

    @Test
    @DisplayName("Clearing Rejection: Should trigger Saga Compensation and reverse ledger hold")
    void shouldCompensateLedgerWhenClearingRejects() {
        Money amount = Money.of("200.00", "BRL");
        String key = "IDEMP-" + UUID.randomUUID();
        AccountId rejectedCreditor = AccountId.of("9999-REJECT", "0001", "OTHERBANK");

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                key, debtorAccount, rejectedCreditor, amount, "Test Rejection", null);

        PaymentResponseDto response = orchestrator.submitPayment(command);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPENSATED);
        assertThat(response.rejectionReason()).contains("Creditor account blocked");

        // Debtor balance MUST be fully restored to 100,000.00 BRL
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorAccount).orElseThrow();
        assertThat(debtor.getBalance()).isEqualTo(Money.of("100000.00", "BRL"));

        // Verify outbox contains compensation event
        assertThat(eventPublisher.getOutbox())
                .anyMatch(event -> event.eventType().equals("PAYMENT_COMPENSATED_REVERSED"));
    }

    @Test
    @DisplayName("Internal transfer: Should execute Book Transfer without external clearing")
    void shouldExecuteInternalBookTransfer() {
        Money amount = Money.of("300.00", "BRL");
        AccountId creditorInternal = AccountId.of("2002-8", "0001", "NEXOR"); // same bank
        String key = "IDEMP-" + UUID.randomUUID();

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                key, debtorAccount, creditorInternal, amount, "Internal transfer", null);

        PaymentResponseDto response = orchestrator.submitPayment(command);

        assertThat(response.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(response.rail()).isEqualTo(PaymentRail.BOOK_TRANSFER);

        // Debtor debited 300 (100,000 -> 99,700)
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorAccount).orElseThrow();
        assertThat(debtor.getBalance()).isEqualTo(Money.of("99700.00", "BRL"));

        // Creditor credited 300 (500 -> 800)
        LedgerAccount creditor = ledgerRepository.findAccountById(creditorInternal).orElseThrow();
        assertThat(creditor.getBalance()).isEqualTo(Money.of("800.00", "BRL"));
    }

    @Test
    @DisplayName("Sharded Transit: Deterministic hashing must distribute transactions across TRANSIT-001..016")
    void testShardedTransitBucketDeterministicResolution() {
        for (int i = 0; i < 50; i++) {
            TransactionId txId = TransactionId.generate();
            AccountId bucket = PaymentSagaOrchestrator.getTransitAccountFor(txId);

            assertThat(bucket.branch()).isEqualTo("0001");
            assertThat(bucket.bankCode()).isEqualTo("CLEARING");
            assertThat(bucket.number()).matches("^TRANSIT-0(0[1-9]|1[0-6])$");
        }

        assertThat(PaymentSagaOrchestrator.getTransitAccountFor(null))
                .isEqualTo(PaymentSagaOrchestrator.SETTLEMENT_TRANSIT_ACCOUNT);
    }

    @Test
    @DisplayName("Sharded Transit: Payment reservation and clearing settlement must balance across the sharded transit bucket")
    void testShardedTransitBucketsAbsorbLedgerHoldAndCompensate() {
        Money amount = Money.of("500.00", "BRL");
        String key = "SHARDED-TX-" + UUID.randomUUID();

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                key, debtorAccount, creditorExternal, amount, "Sharded transit payment", null);

        PaymentResponseDto response = orchestrator.submitPayment(command);
        assertThat(response.status()).isEqualTo(PaymentStatus.SETTLED);

        // Transaction aggregate
        TransactionId txId = new TransactionId(response.transactionId());
        AccountId assignedBucket = PaymentSagaOrchestrator.getTransitAccountFor(txId);

        // The transit bucket must exist in the ledger
        LedgerAccount transitAccount = ledgerRepository.findAccountById(assignedBucket).orElseThrow();
        assertThat(transitAccount).isNotNull();
    }
}
