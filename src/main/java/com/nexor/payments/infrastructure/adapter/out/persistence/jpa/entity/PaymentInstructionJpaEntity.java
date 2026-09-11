package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity;

import com.nexor.payments.domain.model.PaymentRail;
import com.nexor.payments.domain.model.PaymentStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_instructions")
public class PaymentInstructionJpaEntity {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "end_to_end_id", unique = true, nullable = false, length = 35)
    private String endToEndId;

    @Column(name = "debtor_account", nullable = false, length = 64)
    private String debtorAccount;

    @Column(name = "creditor_account", nullable = false, length = 64)
    private String creditorAccount;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 32)
    private PaymentRail rail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "clearing_reference", length = 64)
    private String clearingReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "remittance_info")
    private String remittanceInfo;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    public PaymentInstructionJpaEntity() {}

    public PaymentInstructionJpaEntity(String transactionId, String endToEndId, String debtorAccount, String creditorAccount,
                                      BigDecimal amount, String currency, PaymentRail rail, PaymentStatus status,
                                      String clearingReference, String failureReason, String remittanceInfo,
                                      Instant createdAt, Instant updatedAt) {
        this.transactionId = transactionId;
        this.endToEndId = endToEndId;
        this.debtorAccount = debtorAccount;
        this.creditorAccount = creditorAccount;
        this.amount = amount;
        this.currency = currency;
        this.rail = rail;
        this.status = status;
        this.clearingReference = clearingReference;
        this.failureReason = failureReason;
        this.remittanceInfo = remittanceInfo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getTransactionId() { return transactionId; }
    public String getEndToEndId() { return endToEndId; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getCreditorAccount() { return creditorAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentRail getRail() { return rail; }
    public PaymentStatus getStatus() { return status; }
    public String getClearingReference() { return clearingReference; }
    public String getFailureReason() { return failureReason; }
    public String getRemittanceInfo() { return remittanceInfo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(PaymentStatus status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setClearingReference(String clearingReference) { this.clearingReference = clearingReference; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
