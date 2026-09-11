package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.nexor.payments.application.port.out.EventPublisherPort;
import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.application.port.out.PaymentRepositoryPort;
import com.nexor.payments.application.port.out.PaymentTransactionCoordinatorPort;
import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.PaymentInstruction;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Enforces single ACID transaction boundaries over payment lifecycle Units of Work.
 * Guarantees that ledger balance modifications, journal entry recordings, payment aggregate
 * state transitions, and transactional outbox event recordings are committed atomically,
 * or rolled back completely if any step fails.
 */
@Primary
@Component
public class JpaPaymentTransactionCoordinatorAdapter implements PaymentTransactionCoordinatorPort {

    private final LedgerRepositoryPort ledgerRepository;
    private final PaymentRepositoryPort paymentRepository;
    private final EventPublisherPort eventPublisher;

    public JpaPaymentTransactionCoordinatorAdapter(
            LedgerRepositoryPort ledgerRepository,
            PaymentRepositoryPort paymentRepository,
            EventPublisherPort eventPublisher) {
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository cannot be null");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicReservation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry holdEntry,
            Map<String, Object> outboxPayload) {
        // All mutations join the same database transaction:
        ledgerRepository.saveAccount(debtor);
        ledgerRepository.saveAccount(transit);
        ledgerRepository.saveJournalEntry(holdEntry);
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_FUNDS_RESERVED",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicBookSettlement(
            PaymentInstruction instruction,
            LedgerAccount transit,
            LedgerAccount creditor,
            JournalEntry settlementEntry,
            Map<String, Object> outboxPayload) {
        ledgerRepository.saveAccount(transit);
        ledgerRepository.saveAccount(creditor);
        ledgerRepository.saveJournalEntry(settlementEntry);
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_SETTLED_INTERNAL",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicClearingSettlement(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload) {
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_SETTLED_CLEARING",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicCompensation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry reversalEntry,
            Map<String, Object> outboxPayload) {
        ledgerRepository.saveAccount(debtor);
        ledgerRepository.saveAccount(transit);
        ledgerRepository.saveJournalEntry(reversalEntry);
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_COMPENSATED_REVERSED",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicPendingInvestigation(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload) {
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_INVESTIGATION_REQUIRED",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicReconciliationSettlement(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload) {
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_RECONCILED_SETTLED",
                outboxPayload
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void executeAtomicReconciliationCompensation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry reversalEntry,
            Map<String, Object> outboxPayload) {
        ledgerRepository.saveAccount(debtor);
        ledgerRepository.saveAccount(transit);
        ledgerRepository.saveJournalEntry(reversalEntry);
        paymentRepository.save(instruction);
        eventPublisher.publishOutboxEvent(
                "Payment",
                instruction.getTransactionId().value(),
                "PAYMENT_RECONCILED_COMPENSATED",
                outboxPayload
        );
    }
}
