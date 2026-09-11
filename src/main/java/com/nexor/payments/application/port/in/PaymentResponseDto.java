package com.nexor.payments.application.port.in;

import com.nexor.payments.domain.model.PaymentRail;
import com.nexor.payments.domain.model.PaymentStatus;

import java.time.Instant;

public record PaymentResponseDto(
        String transactionId,
        String endToEndId,
        String debtorAccount,
        String creditorAccount,
        String amount,
        String currency,
        PaymentRail rail,
        PaymentStatus status,
        String clearingReference,
        String rejectionReason,
        Instant processedAt
) {}
