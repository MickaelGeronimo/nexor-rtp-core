package com.nexor.payments.domain.model;

import com.nexor.payments.domain.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root representing a financial payment instruction.
 * Enforces valid state transitions and lifecycle integrity.
 */
public class PaymentInstruction {

    private final TransactionId transactionId;
    private final EndToEndId endToEndId;
    private final AccountId debtorAccountId;
    private final AccountId creditorAccountId;
    private final Money amount;
    private PaymentRail rail;
    private PaymentStatus status;
    private final String remittanceInformation;
    private final Instant createdAt;
    private Instant updatedAt;
    private String clearingReference;
    private String failureReason;

    public PaymentInstruction(TransactionId transactionId,
                              EndToEndId endToEndId,
                              AccountId debtorAccountId,
                              AccountId creditorAccountId,
                              Money amount,
                              PaymentRail rail,
                              String remittanceInformation) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId cannot be null");
        this.endToEndId = Objects.requireNonNull(endToEndId, "endToEndId cannot be null");
        this.debtorAccountId = Objects.requireNonNull(debtorAccountId, "debtorAccountId cannot be null");
        this.creditorAccountId = Objects.requireNonNull(creditorAccountId, "creditorAccountId cannot be null");
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        this.rail = Objects.requireNonNull(rail, "rail cannot be null");
        this.remittanceInformation = remittanceInformation != null ? remittanceInformation : "";
        this.status = PaymentStatus.INITIATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    private void transitionTo(PaymentStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(this.status, newStatus);
        }
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void markValidated() {
        transitionTo(PaymentStatus.VALIDATED);
    }

    public void markFraudApproved() {
        transitionTo(PaymentStatus.FRAUD_APPROVED);
    }

    public void markFundsReserved() {
        transitionTo(PaymentStatus.FUNDS_RESERVED);
    }

    public void markClearingSubmitted(String clearingRef) {
        this.clearingReference = clearingRef;
        transitionTo(PaymentStatus.CLEARING_SUBMITTED);
    }

    public void markSettled() {
        transitionTo(PaymentStatus.SETTLED);
    }

    public void markRejectedValidation(String reason) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.REJECTED_VALIDATION);
    }

    public void markRejectedFraud(String reason) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.REJECTED_FRAUD);
    }

    public void markRejectedClearing(String reason) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.REJECTED_CLEARING);
    }

    public void markPendingInvestigation(String reason) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.PENDING_INVESTIGATION);
    }

    public void markCompensating() {
        transitionTo(PaymentStatus.COMPENSATING);
    }

    public void markCompensated() {
        transitionTo(PaymentStatus.COMPENSATED);
    }

    public void markFailed(String reason) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.FAILED);
    }

    public void setRail(PaymentRail rail) {
        this.rail = Objects.requireNonNull(rail, "rail cannot be null");
        this.updatedAt = Instant.now();
    }

    public TransactionId getTransactionId() {
        return transactionId;
    }

    public EndToEndId getEndToEndId() {
        return endToEndId;
    }

    public AccountId getDebtorAccountId() {
        return debtorAccountId;
    }

    public AccountId getCreditorAccountId() {
        return creditorAccountId;
    }

    public Money getAmount() {
        return amount;
    }

    public PaymentRail getRail() {
        return rail;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getRemittanceInformation() {
        return remittanceInformation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getClearingReference() {
        return clearingReference;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
