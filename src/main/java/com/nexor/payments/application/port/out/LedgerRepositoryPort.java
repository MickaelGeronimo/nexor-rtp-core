package com.nexor.payments.application.port.out;

import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.AccountId;

import java.util.Optional;

public interface LedgerRepositoryPort {
    Optional<LedgerAccount> findAccountById(AccountId accountId);
    void saveAccount(LedgerAccount account);
    void saveJournalEntry(JournalEntry entry);
    java.util.List<JournalEntry> getJournal();
}
