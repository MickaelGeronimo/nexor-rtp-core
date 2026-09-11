package com.nexor.payments.infrastructure.adapter.out.clearing;

import com.nexor.payments.application.port.out.*;
import com.nexor.payments.domain.iso20022.Pacs002StatusReport;
import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.ledger.PostingLeg;
import com.nexor.payments.domain.model.PaymentInstruction;
import com.nexor.payments.domain.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.nexor.payments.application.saga.PaymentSagaOrchestrator.SETTLEMENT_TRANSIT_ACCOUNT;

/**
 * Asynchronous Payment Reconciliation Worker.
 * Resolves payments trapped in PENDING_INVESTIGATION due to clearing rail timeouts.
 *
 * <p><b>Architectural Invariant (ADR-006):</b>
 * Never hold open a database connection/transaction during an external network call.
 * <ul>
 *   <li><b>Phase 1 (External Network Call)</b>: Query Central Bank / clearing rail (pacs.028 inquiry)
 *       with <i>zero database connections or transactions open</i>.</li>
 *   <li><b>Phase 2 (Scoped Local ACID Unit of Work)</b>: Upon authoritative response (ACSC or RJCT),
 *       execute the settlement or compensating reversal within a discrete, atomic transaction
 *       coordinated by {@link PaymentTransactionCoordinatorPort}.</li>
 * </ul>
 */
@Component
public class PaymentReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationWorker.class);

    private final PaymentRepositoryPort paymentRepository;
    private final LedgerRepositoryPort ledgerRepository;
    private final ClearingRailPort clearingRailPort;
    private final EventPublisherPort eventPublisher;
    private final PaymentTransactionCoordinatorPort transactionCoordinator;

    @Autowired
    public PaymentReconciliationWorker(
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            ClearingRailPort clearingRailPort,
            EventPublisherPort eventPublisher,
            PaymentTransactionCoordinatorPort transactionCoordinator) {
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.clearingRailPort = clearingRailPort;
        this.eventPublisher = eventPublisher;
        this.transactionCoordinator = transactionCoordinator;
    }

    /**
     * Fallback constructor for pure unit testing without a Spring context.
     */
    public PaymentReconciliationWorker(
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            ClearingRailPort clearingRailPort,
            EventPublisherPort eventPublisher) {
        this(paymentRepository, ledgerRepository, clearingRailPort, eventPublisher,
                createDefaultCoordinator(ledgerRepository, paymentRepository, eventPublisher));
    }

    /**
     * Reconciles a pending instruction.
     * <p>Intentionally NOT @Transactional: The external network call must execute outside
     * any database transaction boundary to avoid connection pool starvation under load.
     */
    public void reconcilePendingInstruction(PaymentInstruction instruction) {
        if (instruction.getStatus() != PaymentStatus.PENDING_INVESTIGATION) {
            return;
        }

        log.info("[RECONCILIATION-ENGINE] Querying clearing rail for pending EndToEndId [{}] (outside DB transaction)", instruction.getEndToEndId());
        
        // 1. External Network Call: ZERO database connections held open!
        Pacs002StatusReport report = clearingRailPort.queryPaymentStatus(instruction.getRail(), instruction.getEndToEndId());

        // 2. Scoped Local ACID Unit of Work based on authoritative status
        if (report.transactionStatus() == Pacs002StatusReport.TransactionStatus.ACSC) {
            // Reconciled as SETTLED: finalize settlement atomically
            instruction.markSettled();
            transactionCoordinator.executeAtomicReconciliationSettlement(
                    instruction,
                    Map.of("endToEndId", instruction.getEndToEndId().value(), "reconciled", true)
            );
            log.info("[RECONCILIATION-ENGINE] EndToEndId [{}] confirmed SETTLED by clearing rail", instruction.getEndToEndId());
        } else {
            // Reconciled as REJECTED: execute compensating reversal in ledger atomically
            compensateReservationInLedger(instruction, report.additionalInformation());
            log.info("[RECONCILIATION-ENGINE] EndToEndId [{}] confirmed REJECTED. Reversed ledger hold.", instruction.getEndToEndId());
        }
    }

    private void compensateReservationInLedger(PaymentInstruction instruction, String reason) {
        LedgerAccount debtor = ledgerRepository.findAccountById(instruction.getDebtorAccountId())
                .orElseThrow(() -> new IllegalStateException("Debtor account missing during reconciliation compensation"));
        LedgerAccount transit = ledgerRepository.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Transit account missing during reconciliation compensation"));

        instruction.markRejectedClearing(reason);
        instruction.markCompensating();

        PostingLeg leg1 = PostingLeg.credit(debtor.getId(), instruction.getAmount(), "Reconciliation Compensation: " + reason);
        PostingLeg leg2 = PostingLeg.debit(transit.getId(), instruction.getAmount(), "Reconciliation Transit Release: " + reason);

        JournalEntry reversalEntry = new JournalEntry(
                "RECON-" + instruction.getTransactionId().value(),
                "Reconciliation reversal for tx " + instruction.getTransactionId(),
                List.of(leg1, leg2)
        );

        debtor.applyLeg(leg1);
        transit.applyLeg(leg2);
        instruction.markCompensated();

        transactionCoordinator.executeAtomicReconciliationCompensation(
                instruction,
                debtor,
                transit,
                reversalEntry,
                Map.of("endToEndId", instruction.getEndToEndId().value(), "reason", reason, "reconciled", true)
        );
    }

    private static PaymentTransactionCoordinatorPort createDefaultCoordinator(
            LedgerRepositoryPort ledgerRepo,
            PaymentRepositoryPort paymentRepo,
            EventPublisherPort eventPub) {
        return new PaymentTransactionCoordinatorPort() {
            @Override
            public void executeAtomicReservation(PaymentInstruction instruction, LedgerAccount debtor, LedgerAccount transit, JournalEntry holdEntry, Map<String, Object> outboxPayload) {
                ledgerRepo.saveAccount(debtor);
                ledgerRepo.saveAccount(transit);
                ledgerRepo.saveJournalEntry(holdEntry);
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_FUNDS_RESERVED", outboxPayload);
            }

            @Override
            public void executeAtomicBookSettlement(PaymentInstruction instruction, LedgerAccount transit, LedgerAccount creditor, JournalEntry settlementEntry, Map<String, Object> outboxPayload) {
                ledgerRepo.saveAccount(transit);
                ledgerRepo.saveAccount(creditor);
                ledgerRepo.saveJournalEntry(settlementEntry);
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_SETTLED_INTERNAL", outboxPayload);
            }

            @Override
            public void executeAtomicClearingSettlement(PaymentInstruction instruction, Map<String, Object> outboxPayload) {
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_SETTLED_CLEARING", outboxPayload);
            }

            @Override
            public void executeAtomicCompensation(PaymentInstruction instruction, LedgerAccount debtor, LedgerAccount transit, JournalEntry reversalEntry, Map<String, Object> outboxPayload) {
                ledgerRepo.saveAccount(debtor);
                ledgerRepo.saveAccount(transit);
                ledgerRepo.saveJournalEntry(reversalEntry);
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_COMPENSATED_REVERSED", outboxPayload);
            }

            @Override
            public void executeAtomicPendingInvestigation(PaymentInstruction instruction, Map<String, Object> outboxPayload) {
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_INVESTIGATION_REQUIRED", outboxPayload);
            }

            @Override
            public void executeAtomicReconciliationSettlement(PaymentInstruction instruction, Map<String, Object> outboxPayload) {
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_RECONCILED_SETTLED", outboxPayload);
            }

            @Override
            public void executeAtomicReconciliationCompensation(PaymentInstruction instruction, LedgerAccount debtor, LedgerAccount transit, JournalEntry reversalEntry, Map<String, Object> outboxPayload) {
                ledgerRepo.saveAccount(debtor);
                ledgerRepo.saveAccount(transit);
                ledgerRepo.saveJournalEntry(reversalEntry);
                paymentRepo.save(instruction);
                eventPub.publishOutboxEvent("Payment", instruction.getTransactionId().value(), "PAYMENT_RECONCILED_COMPENSATED", outboxPayload);
            }
        };
    }
}
