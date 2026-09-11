package com.nexor.payments.application.port.in;

import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;

public record SubmitPaymentCommand(
        String idempotencyKey,
        AccountId debtorAccount,
        AccountId creditorAccount,
        Money amount,
        String remittanceInformation,
        String requestedRail // Optional, auto-routed if null
) {}
