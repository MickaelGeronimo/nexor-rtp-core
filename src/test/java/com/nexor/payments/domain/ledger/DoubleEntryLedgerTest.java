package com.nexor.payments.domain.ledger;

import com.nexor.payments.domain.exception.InsufficientFundsException;
import com.nexor.payments.domain.exception.LedgerImbalanceException;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Double-Entry Bookkeeping Ledger Unit Tests")
class DoubleEntryLedgerTest {

    @Test
    @DisplayName("Should post balanced journal entry successfully when debits equal credits")
    void shouldPostBalancedJournalEntry() {
        AccountId customerAcc = AccountId.of("ACC-001", "0001", "BANK");
        AccountId transitAcc = AccountId.of("TRANSIT", "0001", "CLEARING");

        LedgerAccount debtor = new LedgerAccount(
                customerAcc, "Customer Deposits", AccountType.LIABILITY, "BRL", Money.of("1000.00", "BRL"), false);
        LedgerAccount transit = new LedgerAccount(
                transitAcc, "Transit", AccountType.LIABILITY, "BRL", Money.of("0.00", "BRL"), true);

        Money amount = Money.of("150.00", "BRL");
        PostingLeg debitLeg = PostingLeg.debit(customerAcc, amount, "Debit Customer");
        PostingLeg creditLeg = PostingLeg.credit(transitAcc, amount, "Credit Transit");

        JournalEntry entry = new JournalEntry("REF-123", "Payment Hold", List.of(debitLeg, creditLeg));

        debtor.applyLeg(debitLeg);
        transit.applyLeg(creditLeg);

        assertThat(entry.getLegs()).hasSize(2);
        // Customer deposit (Liability) decreases when debited
        assertThat(debtor.getBalance()).isEqualTo(Money.of("850.00", "BRL"));
        // Transit deposit (Liability) increases when credited
        assertThat(transit.getBalance()).isEqualTo(Money.of("150.00", "BRL"));
    }

    @Test
    @DisplayName("Should throw LedgerImbalanceException when debits do not equal credits")
    void shouldRejectImbalancedJournalEntry() {
        AccountId acc1 = AccountId.of("ACC-001", "0001", "BANK");
        AccountId acc2 = AccountId.of("ACC-002", "0001", "BANK");

        PostingLeg debitLeg = PostingLeg.debit(acc1, Money.of("100.00", "BRL"), "Debit");
        PostingLeg creditLeg = PostingLeg.credit(acc2, Money.of("99.99", "BRL"), "Imbalanced Credit");

        assertThatThrownBy(() -> new JournalEntry("REF-BAD", "Corrupt Entry", List.of(debitLeg, creditLeg)))
                .isInstanceOf(LedgerImbalanceException.class)
                .hasMessageContaining("Accounting equation broken");
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when debit exceeds available liability balance without overdraft")
    void shouldEnforceNoOverdraftOnCustomerDeposit() {
        AccountId customerAcc = AccountId.of("ACC-001", "0001", "BANK");
        LedgerAccount debtor = new LedgerAccount(
                customerAcc, "Customer Deposits", AccountType.LIABILITY, "BRL", Money.of("50.00", "BRL"), false);

        PostingLeg debitLeg = PostingLeg.debit(customerAcc, Money.of("100.00", "BRL"), "Overdraft Attempt");

        assertThatThrownBy(() -> debtor.applyLeg(debitLeg))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");
    }
}
