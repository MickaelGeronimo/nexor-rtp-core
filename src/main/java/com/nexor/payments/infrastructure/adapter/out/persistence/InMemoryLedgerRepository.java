package com.nexor.payments.infrastructure.adapter.out.persistence;

import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.application.saga.PaymentSagaOrchestrator;
import com.nexor.payments.domain.ledger.*;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryLedgerRepository implements LedgerRepositoryPort {

    private final Map<String, LedgerAccount> accounts = new ConcurrentHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    public InMemoryLedgerRepository() {
        seedAccounts();
    }

    private void seedAccounts() {
        // Clearing Settlement Transit Accounts (Sharded Transit Buckets 1..16)
        for (int i = 1; i <= PaymentSagaOrchestrator.SHARDED_TRANSIT_BUCKETS; i++) {
            AccountId transitId = AccountId.of(String.format("TRANSIT-%03d", i), "0001", "CLEARING");
            accounts.put(transitId.toString(),
                    new LedgerAccount(
                            transitId,
                            "Central Clearing Transit Buffer #" + i,
                            AccountType.LIABILITY,
                            "BRL",
                            Money.of("0.00", "BRL"),
                            true
                    ));
        }

        // Sample Customer Debtor Account (Itaú / Nubank Checking)
        AccountId debtor = AccountId.of("1001-9", "0001", "NEXOR");
        accounts.put(debtor.toString(),
                new LedgerAccount(
                        debtor,
                        "Corporate Checking - Debtor",
                        AccountType.LIABILITY,
                        "BRL",
                        Money.of("100000.00", "BRL"),
                        false
                ));

        // Sample Customer Creditor Account
        AccountId creditor = AccountId.of("2002-8", "0001", "NEXOR");
        accounts.put(creditor.toString(),
                new LedgerAccount(
                        creditor,
                        "Merchant Receiving - Creditor",
                        AccountType.LIABILITY,
                        "BRL",
                        Money.of("500.00", "BRL"),
                        false
                ));

        // Central Bank Reserve (Asset Account)
        AccountId bacenReserve = AccountId.of("BACEN-RESERVE", "0001", "BACEN");
        accounts.put(bacenReserve.toString(),
                new LedgerAccount(
                        bacenReserve,
                        "Bacen SPI Settlement Reserve Account",
                        AccountType.ASSET,
                        "BRL",
                        Money.of("50000000.00", "BRL"),
                        false
                ));
    }

    @Override
    public Optional<LedgerAccount> findAccountById(AccountId accountId) {
        return Optional.ofNullable(accounts.get(accountId.toString()));
    }

    @Override
    public void saveAccount(LedgerAccount account) {
        accounts.put(account.getId().toString(), account);
    }

    @Override
    public synchronized void saveJournalEntry(JournalEntry entry) {
        journal.add(entry);
    }

    @Override
    public synchronized List<JournalEntry> getJournal() {
        return List.copyOf(journal);
    }
}
