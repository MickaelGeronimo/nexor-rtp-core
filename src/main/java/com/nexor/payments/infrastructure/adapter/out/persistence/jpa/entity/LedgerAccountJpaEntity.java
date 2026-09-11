package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity;

import com.nexor.payments.domain.ledger.AccountType;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccountJpaEntity {

    @Id
    @Column(name = "account_id", length = 64)
    private String accountId;

    @Column(name = "account_name", nullable = false, length = 128)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal balance;

    @Column(name = "allow_overdraft", nullable = false)
    private boolean allowOverdraft;

    @Version
    @Column(name = "version")
    private Long version;

    public LedgerAccountJpaEntity() {}

    public LedgerAccountJpaEntity(String accountId, String accountName, AccountType accountType, String currency, BigDecimal balance, boolean allowOverdraft) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = balance;
        this.allowOverdraft = allowOverdraft;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public boolean isAllowOverdraft() { return allowOverdraft; }
    public void setAllowOverdraft(boolean allowOverdraft) { this.allowOverdraft = allowOverdraft; }
    public Long getVersion() { return version; }
}
