package com.nexor.payments.application.port.in;

public interface SubmitPaymentUseCase {
    PaymentResponseDto submitPayment(SubmitPaymentCommand command);
}
