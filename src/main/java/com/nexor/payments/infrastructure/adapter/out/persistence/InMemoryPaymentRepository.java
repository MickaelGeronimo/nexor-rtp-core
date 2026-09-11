package com.nexor.payments.infrastructure.adapter.out.persistence;

import com.nexor.payments.application.port.out.PaymentRepositoryPort;
import com.nexor.payments.domain.model.PaymentInstruction;
import com.nexor.payments.domain.model.TransactionId;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryPaymentRepository implements PaymentRepositoryPort {

    private final Map<String, PaymentInstruction> store = new ConcurrentHashMap<>();

    @Override
    public PaymentInstruction save(PaymentInstruction instruction) {
        store.put(instruction.getTransactionId().value(), instruction);
        return instruction;
    }

    @Override
    public Optional<PaymentInstruction> findById(TransactionId id) {
        return Optional.ofNullable(store.get(id.value()));
    }
}
