package com.nexor.payments.application.saga;

import com.nexor.payments.application.fraud.FraudEvaluationResult;
import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.port.in.SubmitPaymentUseCase;
import com.nexor.payments.application.port.out.*;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.domain.iso20022.Pacs002StatusReport;
import com.nexor.payments.domain.iso20022.Pacs008CreditTransfer;
import com.nexor.payments.domain.ledger.*;
import com.nexor.payments.domain.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates the payment processing lifecycle using the Saga pattern.
 * Provides guaranteed forward settlement or automatic reverse ledger compensation.
 * 
 * Financial operations are demarcated into atomic ACID Units of Work via PaymentTransactionCoordinatorPort,
 * ensuring no database row locks are held across remote network clearing calls (SPI / FedNow).
 */
public class PaymentSagaOrchestrator implements SubmitPaymentUseCase {

    private final PaymentRepositoryPort paymentRepository;
    private final LedgerRepositoryPort ledgerRepository;
    private final ClearingRailPort clearingRailPort;
    private final EventPublisherPort eventPublisher;
    private final IdempotencyStoragePort idempotencyStorage;
    private final FraudScreeningChain fraudChain;
    private final SmartRailRouter railRouter;
    private final PaymentTransactionCoordinatorPort transactionCoordinator;

    // Standard clearing settlement transit account
    public static final AccountId SETTLEMENT_TRANSIT_ACCOUNT = AccountId.of("TRANSIT-001", "0001", "CLEARING");

    public PaymentSagaOrchestrator(
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            ClearingRailPort clearingRailPort,
            EventPublisherPort eventPublisher,
            IdempotencyStoragePort idempotencyStorage,
            FraudScreeningChain fraudChain,
            SmartRailRouter railRouter,
            PaymentTransactionCoordinatorPort transactionCoordinator) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository cannot be null");
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository cannot be null");
        this.clearingRailPort = Objects.requireNonNull(clearingRailPort, "clearingRailPort cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.idempotencyStorage = Objects.requireNonNull(idempotencyStorage, "idempotencyStorage cannot be null");
        this.fraudChain = Objects.requireNonNull(fraudChain, "fraudChain cannot be null");
        this.railRouter = Objects.requireNonNull(railRouter, "railRouter cannot be null");
        this.transactionCoordinator = Objects.requireNonNull(transactionCoordinator, "transactionCoordinator cannot be null");
    }

    public PaymentSagaOrchestrator(
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            ClearingRailPort clearingRailPort,
            EventPublisherPort eventPublisher,
            IdempotencyStoragePort idempotencyStorage,
            FraudScreeningChain fraudChain,
            SmartRailRouter railRouter) {
        this(paymentRepository, ledgerRepository, clearingRailPort, eventPublisher, idempotencyStorage,
             fraudChain, railRouter, createDefaultCoordinator(ledgerRepository, paymentRepository, eventPublisher));
    }

    @Override
    public PaymentResponseDto submitPayment(SubmitPaymentCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        // 1. Compute Request Fingerprint & Handle Idempotency
        String fingerprint = computeFingerprint(command);
        var acquireResult = idempotencyStorage.tryAcquire(command.idempotencyKey(), fingerprint);

        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.ALREADY_COMPLETED) {
            return acquireResult.cachedResponse();
        }
        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.CONFLICTING_PAYLOAD) {
            throw new IllegalArgumentException(
                    "Idempotency Key [" + command.idempotencyKey() + "] already used with a different request payload.");
        }
        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.CONCURRENT_EXECUTION) {
            throw new IllegalStateException(
                    "A transaction with Idempotency Key [" + command.idempotencyKey() + "] is currently being processed.");
        }

        try {
            PaymentResponseDto response = executeSaga(command);
            idempotencyStorage.markCompleted(command.idempotencyKey(), response);
            return response;
        } catch (Exception ex) {
            idempotencyStorage.releaseLock(command.idempotencyKey());
            throw ex;
        }
    }

    private PaymentResponseDto executeSaga(SubmitPaymentCommand command) {
        // Step 1: Input Validation
        if (command.debtorAccount().equals(command.creditorAccount())) {
            throw new IllegalArgumentException("Debtor and creditor accounts cannot be identical");
        }
        if (!command.amount().isPositive()) {
            throw new IllegalArgumentException("Payment amount must be strictly positive");
        }

        // Step 2: Route Determination
        PaymentRail rail = railRouter.determineRail(
                command.debtorAccount(),
                command.creditorAccount(),
                command.amount(),
                command.requestedRail()
        );

        // Step 3: Initialize Domain Aggregate
        TransactionId txId = TransactionId.generate();
        EndToEndId endToEndId = EndToEndId.generate(rail == PaymentRail.PIX ? "E" : "US");
        PaymentInstruction instruction = new PaymentInstruction(
                txId,
                endToEndId,
                command.debtorAccount(),
                command.creditorAccount(),
                command.amount(),
                rail,
                command.remittanceInformation()
        );
        instruction.markValidated();
        paymentRepository.save(instruction);

        // Step 4: Fraud & Sanctions Screening Chain
        FraudEvaluationResult fraudResult = fraudChain.evaluate(instruction);
        if (fraudResult.status() == FraudEvaluationResult.Status.REJECTED) {
            instruction.markRejectedFraud(fraudResult.description());
            paymentRepository.save(instruction);
            eventPublisher.publishOutboxEvent("Payment", txId.value(), "PAYMENT_FRAUD_REJECTED",
                    Map.of("endToEndId", endToEndId.value(), "rule", fraudResult.ruleName(), "reason", fraudResult.description()));
            return toResponse(instruction);
        }
        instruction.markFraudApproved();

        // Step 5: ACID Unit of Work - Ledger Hold / Reservation
        reserveFundsInLedger(instruction);

        // Step 6: Dispatch to Rail / Clearing
        if (rail == PaymentRail.BOOK_TRANSFER) {
            settleBookTransfer(instruction);
            return toResponse(instruction);
        }

        // External Clearing via ISO 20022 (Network phase - NO database transaction held!)
        Pacs008CreditTransfer pacs008 = Pacs008CreditTransfer.of(
                instruction.getEndToEndId(),
                instruction.getAmount(),
                instruction.getDebtorAccountId(),
                "Debtor Customer",
                instruction.getCreditorAccountId(),
                "Creditor Customer",
                instruction.getRemittanceInformation()
        );

        Pacs002StatusReport statusReport = clearingRailPort.dispatchPayment(rail, pacs008);
        instruction.markClearingSubmitted(statusReport.messageId());

        // Step 7: ACID Unit of Work - Finalize Settlement, Compensate, or Hold for Investigation
        if (statusReport.transactionStatus() == Pacs002StatusReport.TransactionStatus.ACSC) {
            // AUTHORITATIVE SUCCESS: Settle
            instruction.markSettled();
            transactionCoordinator.executeAtomicClearingSettlement(
                    instruction,
                    Map.of("endToEndId", endToEndId.value(), "rail", rail.name(), "clearingRef", statusReport.messageId())
            );
        } else if (statusReport.transactionStatus() == Pacs002StatusReport.TransactionStatus.PDNG) {
            // TIMEOUT != FAILURE: Do NOT prematurely compensate!
            // Hold funds in transit buffer and flag for asynchronous reconciliation inquiry
            instruction.markPendingInvestigation(statusReport.additionalInformation());
            transactionCoordinator.executeAtomicPendingInvestigation(
                    instruction,
                    Map.of("endToEndId", endToEndId.value(), "rail", rail.name(), "reason", statusReport.additionalInformation())
            );
        } else {
            // SAGA COMPENSATION: Authoritative clearing rejection confirmed
            instruction.markRejectedClearing(statusReport.additionalInformation());
            compensateReservationInLedger(instruction, statusReport.additionalInformation());
        }

        return toResponse(instruction);
    }

    private void reserveFundsInLedger(PaymentInstruction instruction) {
        LedgerAccount debtor = ledgerRepository.findAccountById(instruction.getDebtorAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Debtor account not found in ledger: " + instruction.getDebtorAccountId()));
        LedgerAccount transit = ledgerRepository.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Transit account not configured: " + SETTLEMENT_TRANSIT_ACCOUNT));

        // Leg 1: Debit Debtor (reducing customer liability deposit)
        // Leg 2: Credit Settlement Transit (increasing clearing liability)
        PostingLeg leg1 = PostingLeg.debit(debtor.getId(), instruction.getAmount(), "Payment Reservation - " + instruction.getEndToEndId());
        PostingLeg leg2 = PostingLeg.credit(transit.getId(), instruction.getAmount(), "Clearing Transit Hold - " + instruction.getEndToEndId());

        JournalEntry entry = new JournalEntry(
                instruction.getTransactionId().value(),
                "Funds Hold for tx " + instruction.getTransactionId(),
                List.of(leg1, leg2)
        );

        debtor.applyLeg(leg1);
        transit.applyLeg(leg2);
        instruction.markFundsReserved();

        transactionCoordinator.executeAtomicReservation(
                instruction,
                debtor,
                transit,
                entry,
                Map.of("endToEndId", instruction.getEndToEndId().value(), "rail", instruction.getRail().name(), "amount", instruction.getAmount().toString())
        );
    }

    private void compensateReservationInLedger(PaymentInstruction instruction, String reason) {
        LedgerAccount debtor = ledgerRepository.findAccountById(instruction.getDebtorAccountId())
                .orElseThrow(() -> new IllegalStateException("Debtor account missing during compensation"));
        LedgerAccount transit = ledgerRepository.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Transit account missing during compensation"));

        instruction.markCompensating();

        // Reverse legs: Credit Debtor, Debit Transit
        PostingLeg leg1 = PostingLeg.credit(debtor.getId(), instruction.getAmount(), "Compensation Reversal: " + reason);
        PostingLeg leg2 = PostingLeg.debit(transit.getId(), instruction.getAmount(), "Transit Release: " + reason);

        JournalEntry reversalEntry = new JournalEntry(
                "COMP-" + instruction.getTransactionId().value(),
                "Reversal compensation for tx " + instruction.getTransactionId(),
                List.of(leg1, leg2)
        );

        debtor.applyLeg(leg1);
        transit.applyLeg(leg2);
        instruction.markCompensated();

        transactionCoordinator.executeAtomicCompensation(
                instruction,
                debtor,
                transit,
                reversalEntry,
                Map.of("endToEndId", instruction.getEndToEndId().value(), "reason", reason)
        );
    }

    private void settleBookTransfer(PaymentInstruction instruction) {
        LedgerAccount transit = ledgerRepository.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Transit account not configured"));
        LedgerAccount creditor = ledgerRepository.findAccountById(instruction.getCreditorAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Creditor account not found: " + instruction.getCreditorAccountId()));

        // Debit Transit, Credit Creditor
        PostingLeg leg1 = PostingLeg.debit(transit.getId(), instruction.getAmount(), "Book Settlement Transit Release");
        PostingLeg leg2 = PostingLeg.credit(creditor.getId(), instruction.getAmount(), "Book Settlement Credit");

        JournalEntry settlementEntry = new JournalEntry(
                "BOOK-" + instruction.getTransactionId().value(),
                "Internal book settlement",
                List.of(leg1, leg2)
        );

        transit.applyLeg(leg1);
        creditor.applyLeg(leg2);
        instruction.markSettled();

        transactionCoordinator.executeAtomicBookSettlement(
                instruction,
                transit,
                creditor,
                settlementEntry,
                Map.of("endToEndId", instruction.getEndToEndId().value(), "rail", instruction.getRail().name())
        );
    }

    private PaymentResponseDto toResponse(PaymentInstruction inst) {
        return new PaymentResponseDto(
                inst.getTransactionId().value(),
                inst.getEndToEndId().value(),
                inst.getDebtorAccountId().toString(),
                inst.getCreditorAccountId().toString(),
                inst.getAmount().getAmount().toPlainString(),
                inst.getAmount().getCurrencyCode(),
                inst.getRail(),
                inst.getStatus(),
                inst.getClearingReference(),
                inst.getFailureReason(),
                inst.getUpdatedAt()
        );
    }

    private String computeFingerprint(SubmitPaymentCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = command.debtorAccount().toString() + "|" +
                         command.creditorAccount().toString() + "|" +
                         command.amount().getAmount().toPlainString() + "|" +
                         command.amount().getCurrencyCode() + "|" +
                         (command.remittanceInformation() != null ? command.remittanceInformation() : "");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
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
