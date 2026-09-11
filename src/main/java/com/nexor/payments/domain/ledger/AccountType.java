package com.nexor.payments.domain.ledger;

public enum AccountType {
    /** Assets: Normal balance DEBIT (e.g. Central Bank Settlement Reserve Account) */
    ASSET,

    /** Liabilities: Normal balance CREDIT (e.g. Customer Demand Deposits) */
    LIABILITY,

    /** Equity: Normal balance CREDIT */
    EQUITY,

    /** Revenue: Normal balance CREDIT (e.g. Transaction Fees) */
    REVENUE,

    /** Expense: Normal balance DEBIT (e.g. Network interchange cost) */
    EXPENSE
}
