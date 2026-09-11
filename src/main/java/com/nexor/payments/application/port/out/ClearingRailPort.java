package com.nexor.payments.application.port.out;

import com.nexor.payments.domain.iso20022.Pacs002StatusReport;
import com.nexor.payments.domain.iso20022.Pacs008CreditTransfer;
import com.nexor.payments.domain.model.EndToEndId;
import com.nexor.payments.domain.model.PaymentRail;

public interface ClearingRailPort {
    Pacs002StatusReport dispatchPayment(PaymentRail rail, Pacs008CreditTransfer pacs008);
    Pacs002StatusReport queryPaymentStatus(PaymentRail rail, EndToEndId endToEndId);
    boolean isRailAvailable(PaymentRail rail);
}
