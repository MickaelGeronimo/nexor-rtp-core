package com.nexor.payments.domain.exception;

public class FraudRejectionException extends RuntimeException {
    private final String ruleName;
    private final String reasonCode;

    public FraudRejectionException(String ruleName, String reasonCode, String message) {
        super(String.format("Payment rejected by AML/Fraud engine [%s:%s]: %s", ruleName, reasonCode, message));
        this.ruleName = ruleName;
        this.reasonCode = reasonCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
