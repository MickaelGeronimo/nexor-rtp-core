package com.nexor.payments.domain.iso20022;

import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.EndToEndId;
import com.nexor.payments.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of an ISO 20022 pacs.008.001.10 Financial Institution
 * Customer Credit Transfer message.
 */
public record Pacs008CreditTransfer(
        String messageId,
        Instant creationDateTime,
        EndToEndId endToEndId,
        String instructionId,
        Money interbankSettlementAmount,
        String settlementMethod,
        AccountId debtorAccount,
        String debtorName,
        AccountId creditorAccount,
        String creditorName,
        String remittanceInformation
) {
    public Pacs008CreditTransfer {
        Objects.requireNonNull(messageId, "messageId cannot be null");
        Objects.requireNonNull(creationDateTime, "creationDateTime cannot be null");
        Objects.requireNonNull(endToEndId, "endToEndId cannot be null");
        Objects.requireNonNull(interbankSettlementAmount, "interbankSettlementAmount cannot be null");
        Objects.requireNonNull(debtorAccount, "debtorAccount cannot be null");
        Objects.requireNonNull(creditorAccount, "creditorAccount cannot be null");
    }

    public static Pacs008CreditTransfer of(
            EndToEndId endToEndId,
            Money amount,
            AccountId debtor,
            String debtorName,
            AccountId creditor,
            String creditorName,
            String remittanceInfo) {
        String msgId = "MSG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new Pacs008CreditTransfer(
                msgId,
                Instant.now(),
                endToEndId,
                "INSTR-" + UUID.randomUUID().toString().substring(0, 8),
                amount,
                "CLRG",
                debtor,
                debtorName != null ? debtorName : "Debtor",
                creditor,
                creditorName != null ? creditorName : "Creditor",
                remittanceInfo
        );
    }
}
