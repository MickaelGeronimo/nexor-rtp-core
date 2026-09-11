package com.nexor.payments.domain.iso20022;

import com.nexor.payments.domain.model.EndToEndId;
import com.nexor.payments.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of an ISO 20022 pacs.004.001.11 Payment Return message
 * (Used for Pix Devoluções / RTP Returns and Reversals).
 */
public record Pacs004PaymentReturn(
        String messageId,
        Instant creationDateTime,
        String originalMessageId,
        EndToEndId originalEndToEndId,
        Money returnedAmount,
        String returnReasonCode,
        String returnReasonDescription
) {
    public Pacs004PaymentReturn {
        Objects.requireNonNull(messageId, "messageId cannot be null");
        Objects.requireNonNull(creationDateTime, "creationDateTime cannot be null");
        Objects.requireNonNull(originalEndToEndId, "originalEndToEndId cannot be null");
        Objects.requireNonNull(returnedAmount, "returnedAmount cannot be null");
        Objects.requireNonNull(returnReasonCode, "returnReasonCode cannot be null");
    }

    public static Pacs004PaymentReturn of(
            String originalMsgId,
            EndToEndId endToEndId,
            Money amount,
            String reasonCode,
            String description) {
        return new Pacs004PaymentReturn(
                "RET-" + UUID.randomUUID().toString().substring(0, 12),
                Instant.now(),
                originalMsgId,
                endToEndId,
                amount,
                reasonCode,
                description
        );
    }
}
