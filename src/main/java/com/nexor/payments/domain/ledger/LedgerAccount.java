package com.nexor.payments.domain.ledger;

import com.nexor.payments.domain.exception.InsufficientFundsException;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;

import java.util.Objects;

/**
 * Domain entity representing an account on the double-entry general ledger.
 * Manages balance mutations according to standard banking accounting conventions:
 * - ASSET: Debit (+) increases balance, Credit (-) decreases balance.
 * - LIABILITY: Credit (+) increases balance, Debit (-) decreases balance.
 */
public class LedgerAccount {

    private final AccountId id;
    private final String name;
    private final AccountType type;
    private final String currency;
    private Money balance;
    private final boolean allowOverdraft;
    private final long version;

    public LedgerAccount(AccountId id, String name, AccountType type, String currency, Money initialBalance, boolean allowOverdraft) {
        this(id, name, type, currency, initialBalance, allowOverdraft, -1L);
    }

    /**
     * @param version the optimistic-lock version this account was read at (from the
     *                {@code @Version} column), or {@code -1} for an account that hasn't been
     *                persisted yet. {@link com.nexor.payments.application.port.out.LedgerRepositoryPort#saveAccount}
     *                uses this to detect a concurrent update that happened between the read that
     *                produced this object and the write — see the class javadoc note below.
     */
    public LedgerAccount(AccountId id, String name, AccountType type, String currency, Money initialBalance, boolean allowOverdraft, long version) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance cannot be null");
        this.allowOverdraft = allowOverdraft;
        this.version = version;
    }

    public synchronized void applyLeg(PostingLeg leg) {
        if (!leg.accountId().equals(this.id)) {
            throw new IllegalArgumentException("Cannot apply posting leg to different account: " + leg.accountId() + " vs " + this.id);
        }

        Money delta = leg.amount();

        switch (this.type) {
            case ASSET -> {
                if (leg.type() == PostingType.DEBIT) {
                    this.balance = this.balance.add(delta);
                } else {
                    this.balance = this.balance.subtract(delta);
                }
            }
            case LIABILITY -> {
                if (leg.type() == PostingType.CREDIT) {
                    this.balance = this.balance.add(delta);
                } else {
                    if (!allowOverdraft && this.balance.isLessThan(delta)) {
                        throw new InsufficientFundsException(this.id, delta, this.balance);
                    }
                    this.balance = this.balance.subtract(delta);
                }
            }
            case EQUITY, REVENUE -> {
                if (leg.type() == PostingType.CREDIT) {
                    this.balance = this.balance.add(delta);
                } else {
                    this.balance = this.balance.subtract(delta);
                }
            }
            case EXPENSE -> {
                if (leg.type() == PostingType.DEBIT) {
                    this.balance = this.balance.add(delta);
                } else {
                    this.balance = this.balance.subtract(delta);
                }
            }
        }
    }

    public AccountId getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public synchronized Money getBalance() {
        return balance;
    }

    public boolean isAllowOverdraft() {
        return allowOverdraft;
    }

    /**
     * Optimistic-lock version this object was read at, or {@code -1} if it hasn't been persisted
     * yet. {@code applyLeg()} mutates {@code balance} in place based on whatever value was
     * present when this object was loaded — it does NOT re-check the database. The version
     * check that guards against a lost update (another process having committed a change to
     * this same account since this object was read) happens in the persistence adapter's
     * {@code saveAccount}, using this value.
     */
    public long getVersion() {
        return version;
    }
}
