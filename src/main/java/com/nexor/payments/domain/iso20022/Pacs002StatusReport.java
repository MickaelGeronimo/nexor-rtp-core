package com.nexor.payments.domain.iso20022;

import com.nexor.payments.domain.model.EndToEndId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of an ISO 20022 pacs.002.001.12 Payment Status Report.
 */
public record Pacs002StatusReport(
        String messageId,
        Instant creationDateTime,
        String originalMessageId,
        EndToEndId originalEndToEndId,
        TransactionStatus transactionStatus,
        String statusReasonCode,
        String additionalInformation
) {
    public enum TransactionStatus {
        ACTC, // Accepted Technical Validation
        ACCP, // Accepted Customer Profile
        ACSC, // Accepted Settlement Completed (Settled)
        PDNG, // Pending
        RJCT  // Rejected
    }

    public Pacs002StatusReport {
        Objects.requireNonNull(messageId, "messageId cannot be null");
        Objects.requireNonNull(creationDateTime, "creationDateTime cannot be null");
        Objects.requireNonNull(originalEndToEndId, "originalEndToEndId cannot be null");
        Objects.requireNonNull(transactionStatus, "transactionStatus cannot be null");
    }

    public static Pacs002StatusReport acceptSettled(String originalMsgId, EndToEndId endToEndId) {
        return new Pacs002StatusReport(
                "REP-" + UUID.randomUUID().toString().substring(0, 12),
                Instant.now(),
                originalMsgId,
                endToEndId,
                TransactionStatus.ACSC,
                null,
                "Settlement completed successfully on clearing rail"
        );
    }

    public static Pacs002StatusReport reject(String originalMsgId, EndToEndId endToEndId, String reasonCode, String description) {
        return new Pacs002StatusReport(
                "REP-" + UUID.randomUUID().toString().substring(0, 12),
                Instant.now(),
                originalMsgId,
                endToEndId,
                TransactionStatus.RJCT,
                reasonCode,
                description
        );
    }

    public static Pacs002StatusReport pendingTimeout(String originalMsgId, EndToEndId endToEndId, String reasonCode, String description) {
        return new Pacs002StatusReport(
                "REP-" + UUID.randomUUID().toString().substring(0, 12),
                Instant.now(),
                originalMsgId,
                endToEndId,
                TransactionStatus.PDNG,
                reasonCode,
                description
        );
    }
}
