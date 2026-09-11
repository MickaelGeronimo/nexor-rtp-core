package com.nexor.payments.infrastructure.adapter.out.clearing;

import com.nexor.payments.application.port.out.ClearingRailPort;
import com.nexor.payments.domain.iso20022.Pacs002StatusReport;
import com.nexor.payments.domain.iso20022.Pacs008CreditTransfer;
import com.nexor.payments.domain.model.PaymentRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Realistic simulator for instant payment clearing networks (Bacen SPI for Pix,
 * FedNow / The Clearing House RTP for USD). Supports fault-injection for testing
 * automated Saga compensating transactions.
 */
@Component
public class MockCentralBankClearingRail implements ClearingRailPort {

    private static final Logger log = LoggerFactory.getLogger(MockCentralBankClearingRail.class);

    private final AtomicBoolean simulateClearingOutage = new AtomicBoolean(false);

    @Override
    public Pacs002StatusReport dispatchPayment(PaymentRail rail, Pacs008CreditTransfer pacs008) {
        log.info("[CLEARING] Dispatching pacs.008 to rail [{}] for EndToEndId [{}] Amount [{}]",
                rail, pacs008.endToEndId(), pacs008.interbankSettlementAmount());

        // Fault Injection Check
        if (simulateClearingOutage.get()) {
            log.warn("[CLEARING] Outage simulation triggered for EndToEndId [{}]", pacs008.endToEndId());
            return Pacs002StatusReport.reject(
                    pacs008.messageId(),
                    pacs008.endToEndId(),
                    "DS27",
                    "Central clearing network currently unreachable (Simulated Outage)"
            );
        }

        // Test convention: Account ending with "-REJECT" triggers central bank rejection
        if (pacs008.creditorAccount().number().endsWith("-REJECT")) {
            log.warn("[CLEARING] Creditor rejected by central bank registry [{}]", pacs008.creditorAccount());
            return Pacs002StatusReport.reject(
                    pacs008.messageId(),
                    pacs008.endToEndId(),
                    "AC01",
                    "Creditor account blocked or non-existent in Central Bank directory"
            );
        }

        // Test convention: Amount ending in 99 cents triggers settlement liquidity rejection
        if (pacs008.interbankSettlementAmount().getAmount().toPlainString().endsWith(".99")) {
            log.warn("[CLEARING] Clearing limit rejection triggered for amount [{}]", pacs008.interbankSettlementAmount());
            return Pacs002StatusReport.reject(
                    pacs008.messageId(),
                    pacs008.endToEndId(),
                    "AM04",
                    "Insufficient interbank clearing reserve balance in central rail"
            );
        }

        // Test convention: Creditor ending with "-TIMEOUT" triggers network acknowledgment timeout
        if (pacs008.creditorAccount().number().endsWith("-TIMEOUT") || pacs008.interbankSettlementAmount().getAmount().toPlainString().endsWith(".88")) {
            log.warn("[CLEARING] Technical timeout simulating packet drop for EndToEndId [{}]", pacs008.endToEndId());
            return Pacs002StatusReport.pendingTimeout(
                    pacs008.messageId(),
                    pacs008.endToEndId(),
                    "AB03",
                    "Clearing rail timeout: acknowledgment not received, state is UNKNOWN"
            );
        }

        // Happy Path: Instant Settlement
        log.info("[CLEARING] Instant settlement confirmed (ACSC) for EndToEndId [{}]", pacs008.endToEndId());
        return Pacs002StatusReport.acceptSettled(pacs008.messageId(), pacs008.endToEndId());
    }

    @Override
    public Pacs002StatusReport queryPaymentStatus(PaymentRail rail, com.nexor.payments.domain.model.EndToEndId endToEndId) {
        log.info("[CLEARING-RECONCILIATION] Querying central bank clearing registry for EndToEndId [{}]", endToEndId);
        // Authoritative reconciliation resolution
        return Pacs002StatusReport.acceptSettled("QUERY-" + endToEndId.value(), endToEndId);
    }

    @Override
    public boolean isRailAvailable(PaymentRail rail) {
        return !simulateClearingOutage.get();
    }

    public void setSimulateClearingOutage(boolean outage) {
        this.simulateClearingOutage.set(outage);
    }
}
