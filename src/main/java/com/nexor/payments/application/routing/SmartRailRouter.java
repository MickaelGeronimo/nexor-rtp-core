package com.nexor.payments.application.routing;

import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.domain.model.PaymentRail;

import java.util.Objects;

/**
 * Intelligent multi-rail decision engine.
 * Selects between internal book transfer (zero clearing fee), Pix (Brazil),
 * FedNow or RTP (US) based on institution identifiers, currency, and availability.
 */
public class SmartRailRouter {

    public PaymentRail determineRail(AccountId debtor, AccountId creditor, Money amount, String requestedRail) {
        Objects.requireNonNull(debtor, "debtor cannot be null");
        Objects.requireNonNull(creditor, "creditor cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");

        // 1. Intra-bank Book Transfer Optimization
        if (debtor.bankCode().equalsIgnoreCase(creditor.bankCode())) {
            return PaymentRail.BOOK_TRANSFER;
        }

        // 2. Explicit Override
        if (requestedRail != null && !requestedRail.isBlank()) {
            try {
                return PaymentRail.valueOf(requestedRail.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fallback to auto-routing
            }
        }

        // 3. Currency-based Instant Rails
        String currency = amount.getCurrencyCode();
        return switch (currency) {
            case "BRL" -> PaymentRail.PIX;
            case "USD" -> PaymentRail.FEDNOW;
            default -> PaymentRail.RTP_TCH;
        };
    }
}
