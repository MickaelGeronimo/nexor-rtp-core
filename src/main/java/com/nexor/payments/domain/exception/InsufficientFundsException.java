package com.nexor.payments.domain.exception;

import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;

public class InsufficientFundsException extends RuntimeException {
    private final AccountId accountId;
    private final Money attemptedAmount;
    private final Money currentBalance;

    public InsufficientFundsException(AccountId accountId, Money attemptedAmount, Money currentBalance) {
        super(String.format("Account %s has insufficient funds. Attempted: %s, Available: %s",
                accountId, attemptedAmount, currentBalance));
        this.accountId = accountId;
        this.attemptedAmount = attemptedAmount;
        this.currentBalance = currentBalance;
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public Money getAttemptedAmount() {
        return attemptedAmount;
    }

    public Money getCurrentBalance() {
        return currentBalance;
    }
}
