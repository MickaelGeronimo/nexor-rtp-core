package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.Money;
import com.nexor.payments.domain.model.PaymentInstruction;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Enforces per-transaction thresholds for instant rails.
 * Any amount exceeding rail limits requires secondary compliance review or rejection.
 */
public class HighValueScreeningRule implements FraudRule {

    private final Map<String, BigDecimal> maxLimitsByCurrency = Map.of(
            "BRL", new BigDecimal("1000000.00"), // 1 Million BRL
            "USD", new BigDecimal("500000.00"),  // 500k USD
            "EUR", new BigDecimal("500000.00")
    );

    @Override
    public String getRuleName() {
        return "HIGH_VALUE_TRANSACTION_LIMIT";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public FraudEvaluationResult evaluate(PaymentInstruction instruction) {
        Money amount = instruction.getAmount();
        BigDecimal limit = maxLimitsByCurrency.getOrDefault(amount.getCurrencyCode(), new BigDecimal("100000.00"));

        if (amount.getAmount().compareTo(limit) > 0) {
            return FraudEvaluationResult.reject(getRuleName(), "LIMIT_EXCEEDED",
                    String.format("Payment amount %s exceeds instant rail ceiling %s %s",
                            amount.getAmount(), limit, amount.getCurrencyCode()));
        }

        return FraudEvaluationResult.approve(getRuleName());
    }
}
