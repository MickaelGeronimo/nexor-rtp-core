package com.nexor.payments.domain.exception;

import com.nexor.payments.domain.model.PaymentStatus;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(PaymentStatus current, PaymentStatus attempted) {
        super(String.format("Illegal state transition from %s to %s", current, attempted));
    }
}
