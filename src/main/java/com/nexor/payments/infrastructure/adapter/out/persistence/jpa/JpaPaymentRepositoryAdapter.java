package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.nexor.payments.application.port.out.PaymentRepositoryPort;
import com.nexor.payments.domain.model.*;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.PaymentInstructionJpaEntity;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Primary
@Repository
public class JpaPaymentRepositoryAdapter implements PaymentRepositoryPort {

    private final SpringDataPaymentRepository paymentRepo;

    public JpaPaymentRepositoryAdapter(SpringDataPaymentRepository paymentRepo) {
        this.paymentRepo = paymentRepo;
    }

    @Override
    @Transactional
    public PaymentInstruction save(PaymentInstruction inst) {
        String txId = inst.getTransactionId().value();

        Optional<PaymentInstructionJpaEntity> existing = paymentRepo.findById(txId);

        if (existing.isPresent()) {
            // UPDATE: mutate only the mutable fields — status, clearingRef, failureReason, updatedAt.
            // This preserves the @Version column and prevents OptimisticLockingFailureException
            // from a phantom re-insert of an entity that already exists.
            PaymentInstructionJpaEntity entity = existing.get();
            entity.setStatus(inst.getStatus());
            entity.setUpdatedAt(inst.getUpdatedAt());
            if (inst.getClearingReference() != null) {
                entity.setClearingReference(inst.getClearingReference());
            }
            if (inst.getFailureReason() != null) {
                entity.setFailureReason(inst.getFailureReason());
            }
            paymentRepo.save(entity);
        } else {
            // INSERT: first time persisting this payment instruction
            PaymentInstructionJpaEntity entity = new PaymentInstructionJpaEntity(
                    txId,
                    inst.getEndToEndId().value(),
                    inst.getDebtorAccountId().toString(),
                    inst.getCreditorAccountId().toString(),
                    inst.getAmount().getAmount(),
                    inst.getAmount().getCurrencyCode(),
                    inst.getRail(),
                    inst.getStatus(),
                    inst.getClearingReference(),
                    inst.getFailureReason(),
                    inst.getRemittanceInformation(),
                    inst.getCreatedAt(),
                    inst.getUpdatedAt()
            );
            paymentRepo.save(entity);
        }
        return inst;
    }

    @Override
    public Optional<PaymentInstruction> findById(TransactionId id) {
        return paymentRepo.findById(id.value()).map(this::toDomain);
    }

    private PaymentInstruction toDomain(PaymentInstructionJpaEntity entity) {
        TransactionId txId = new TransactionId(entity.getTransactionId());
        EndToEndId e2eId = new EndToEndId(entity.getEndToEndId());
        AccountId debtor = AccountId.simple(entity.getDebtorAccount());
        AccountId creditor = AccountId.simple(entity.getCreditorAccount());
        Money amount = Money.of(entity.getAmount(), entity.getCurrency());

        PaymentInstruction inst = new PaymentInstruction(
                txId, e2eId, debtor, creditor, amount, entity.getRail(), entity.getRemittanceInfo()
        );

        // Advance state machine to reflect persisted state
        switch (entity.getStatus()) {
            case VALIDATED -> inst.markValidated();
            case FRAUD_APPROVED -> { inst.markValidated(); inst.markFraudApproved(); }
            case FUNDS_RESERVED -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); }
            case CLEARING_SUBMITTED -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); inst.markClearingSubmitted(entity.getClearingReference()); }
            case SETTLED -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); inst.markSettled(); }
            case REJECTED_VALIDATION -> inst.markRejectedValidation(entity.getFailureReason());
            case REJECTED_FRAUD -> { inst.markValidated(); inst.markRejectedFraud(entity.getFailureReason()); }
            case REJECTED_CLEARING -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); inst.markClearingSubmitted(entity.getClearingReference()); inst.markRejectedClearing(entity.getFailureReason()); }
            case PENDING_INVESTIGATION -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); inst.markClearingSubmitted(entity.getClearingReference()); inst.markPendingInvestigation(entity.getFailureReason()); }
            case COMPENSATED -> { inst.markValidated(); inst.markFraudApproved(); inst.markFundsReserved(); inst.markClearingSubmitted(entity.getClearingReference()); inst.markRejectedClearing(entity.getFailureReason()); inst.markCompensating(); inst.markCompensated(); }
            case FAILED -> inst.markFailed(entity.getFailureReason());
            default -> {}
        }
        return inst;
    }
}
