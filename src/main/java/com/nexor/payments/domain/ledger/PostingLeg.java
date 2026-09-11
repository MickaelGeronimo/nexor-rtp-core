package com.nexor.payments.domain.ledger;

import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;

import java.util.Objects;

/**
 * An individual leg (debit or credit) within a double-entry journal transaction.
 */
public record PostingLeg(AccountId accountId, PostingType type, Money amount, String description) {
    public PostingLeg {
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amount must be strictly positive, was: " + amount);
        }
    }

    public static PostingLeg debit(AccountId accountId, Money amount, String description) {
        return new PostingLeg(accountId, PostingType.DEBIT, amount, description);
    }

    public static PostingLeg credit(AccountId accountId, Money amount, String description) {
        return new PostingLeg(accountId, PostingType.CREDIT, amount, description);
    }
}
