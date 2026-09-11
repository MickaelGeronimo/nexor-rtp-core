package com.nexor.payments.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strict finite-state machine representing the lifecycle of an instant payment instruction.
 * Guarantees that payment state transitions cannot bypass fraud, validation, or compensation steps.
 * Includes explicit support for UNKNOWN / TIMEOUT states (PENDING_INVESTIGATION) where timeout != failure.
 */
public enum PaymentStatus {
    INITIATED,
    VALIDATED,
    FRAUD_APPROVED,
    FUNDS_RESERVED,
    CLEARING_SUBMITTED,
    PENDING_INVESTIGATION, // Timeout / Unknown network state - requires reconciliation
    SETTLED,
    REJECTED_VALIDATION,
    REJECTED_FRAUD,
    REJECTED_CLEARING,
    COMPENSATING,
    COMPENSATED,
    FAILED;

    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED_VALIDATION || this == REJECTED_FRAUD 
                || this == COMPENSATED || this == FAILED;
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case INITIATED -> EnumSet.of(VALIDATED, REJECTED_VALIDATION, FAILED).contains(next);
            case VALIDATED -> EnumSet.of(FRAUD_APPROVED, REJECTED_FRAUD, FAILED).contains(next);
            case FRAUD_APPROVED -> EnumSet.of(FUNDS_RESERVED, FAILED).contains(next);
            case FUNDS_RESERVED -> EnumSet.of(CLEARING_SUBMITTED, SETTLED, COMPENSATING, FAILED).contains(next);
            case CLEARING_SUBMITTED -> EnumSet.of(SETTLED, REJECTED_CLEARING, PENDING_INVESTIGATION, COMPENSATING, FAILED).contains(next);
            case PENDING_INVESTIGATION -> EnumSet.of(SETTLED, REJECTED_CLEARING, COMPENSATING, FAILED).contains(next);
            case REJECTED_CLEARING -> EnumSet.of(COMPENSATING, FAILED).contains(next);
            case COMPENSATING -> EnumSet.of(COMPENSATED, FAILED).contains(next);
            case SETTLED, REJECTED_VALIDATION, REJECTED_FRAUD, COMPENSATED, FAILED -> false;
        };
    }
}
