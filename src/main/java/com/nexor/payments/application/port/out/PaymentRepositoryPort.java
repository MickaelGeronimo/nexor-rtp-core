package com.nexor.payments.application.port.out;

import com.nexor.payments.domain.model.PaymentInstruction;
import com.nexor.payments.domain.model.TransactionId;

import java.util.Optional;

public interface PaymentRepositoryPort {
    PaymentInstruction save(PaymentInstruction instruction);
    Optional<PaymentInstruction> findById(TransactionId id);
}
