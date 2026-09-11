package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.PaymentInstruction;

public interface FraudRule {
    String getRuleName();
    int getOrder();
    FraudEvaluationResult evaluate(PaymentInstruction instruction);
}
