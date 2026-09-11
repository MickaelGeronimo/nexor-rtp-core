package com.nexor.payments.application.fraud;

public record FraudEvaluationResult(
        Status status,
        String ruleName,
        String reasonCode,
        String description
) {
    public enum Status {
        APPROVED,
        FLAGGED,
        REJECTED
    }

    public static FraudEvaluationResult approve(String ruleName) {
        return new FraudEvaluationResult(Status.APPROVED, ruleName, "NONE", "Rule evaluated successfully without alerts");
    }

    public static FraudEvaluationResult reject(String ruleName, String reasonCode, String description) {
        return new FraudEvaluationResult(Status.REJECTED, ruleName, reasonCode, description);
    }
}
