package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.PaymentInstruction;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Executes a Chain of Responsibility for fraud, AML, and sanctions checks.
 * Stops immediately upon any fatal rejection.
 */
public class FraudScreeningChain {

    private final List<FraudRule> rules;

    public FraudScreeningChain(List<FraudRule> rules) {
        Objects.requireNonNull(rules, "rules cannot be null");
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(FraudRule::getOrder))
                .toList();
    }

    public static FraudScreeningChain createDefault() {
        return new FraudScreeningChain(List.of(
                new SanctionsScreeningRule(),
                new HighValueScreeningRule(),
                new VelocityCheckRule(10_000)
        ));
    }

    public FraudEvaluationResult evaluate(PaymentInstruction instruction) {
        for (FraudRule rule : rules) {
            FraudEvaluationResult result = rule.evaluate(instruction);
            if (result.status() == FraudEvaluationResult.Status.REJECTED) {
                return result; // Short-circuit on rejection
            }
        }
        return FraudEvaluationResult.approve("ALL_RULES_PASSED");
    }
}
