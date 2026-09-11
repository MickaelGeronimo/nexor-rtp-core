package com.nexor.payments.application.port.out;

import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.PaymentInstruction;

import java.util.Map;

/**
 * Port defining the ACID transactional boundaries (Units of Work) for the payment lifecycle.
 * In a distributed Saga, external network clearing calls (SPI / FedNow) cannot be executed
 * within an open database transaction (which would exhaust the connection pool).
 * 
 * Instead, the lifecycle is demarcated into distinct, atomic local database transactions:
 * 1. Reservation Phase (Debtor Debit + Transit Credit + JournalEntry + Payment + Outbox)
 * 2. Settlement Phase (Transit Debit + Creditor Credit + JournalEntry + Payment + Outbox)
 * 3. Compensation Phase (Debtor Credit + Transit Debit + Reversal JournalEntry + Payment + Outbox)
 * 4. Investigation Phase (Payment State + Outbox)
 */
public interface PaymentTransactionCoordinatorPort {

    /**
     * Atomically executes the reservation unit of work in a single ACID transaction.
     */
    void executeAtomicReservation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry holdEntry,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically executes internal book transfer settlement in a single ACID transaction.
     */
    void executeAtomicBookSettlement(
            PaymentInstruction instruction,
            LedgerAccount transit,
            LedgerAccount creditor,
            JournalEntry settlementEntry,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically executes clearing settlement finalization in a single ACID transaction.
     */
    void executeAtomicClearingSettlement(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically executes clearing compensation reversal in a single ACID transaction.
     */
    void executeAtomicCompensation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry reversalEntry,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically marks the payment as pending investigation in a single ACID transaction.
     */
    void executeAtomicPendingInvestigation(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically executes reconciliation clearing settlement in a single ACID transaction.
     * Emits PAYMENT_RECONCILED_SETTLED outbox event.
     */
    void executeAtomicReconciliationSettlement(
            PaymentInstruction instruction,
            Map<String, Object> outboxPayload
    );

    /**
     * Atomically executes reconciliation compensation reversal in a single ACID transaction.
     * Emits PAYMENT_RECONCILED_COMPENSATED outbox event.
     */
    void executeAtomicReconciliationCompensation(
            PaymentInstruction instruction,
            LedgerAccount debtor,
            LedgerAccount transit,
            JournalEntry reversalEntry,
            Map<String, Object> outboxPayload
    );
}
