package com.nexor.payments.infrastructure.adapter.out.persistence.jpa;

import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.application.saga.PaymentSagaOrchestrator;
import com.nexor.payments.domain.ledger.*;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.*;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataJournalEntryRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataLedgerAccountRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Primary
@Repository
public class JpaLedgerRepositoryAdapter implements LedgerRepositoryPort {

    private final SpringDataLedgerAccountRepository accountRepo;
    private final SpringDataJournalEntryRepository journalRepo;

    public JpaLedgerRepositoryAdapter(
            SpringDataLedgerAccountRepository accountRepo,
            SpringDataJournalEntryRepository journalRepo) {
        this.accountRepo = accountRepo;
        this.journalRepo = journalRepo;
    }

    @PostConstruct
    public void seedInitialAccountsIfEmpty() {
        if (accountRepo.count() == 0) {
            // Clearing Settlement Transit Accounts (Sharded Transit Buckets 1..16)
            for (int i = 1; i <= PaymentSagaOrchestrator.SHARDED_TRANSIT_BUCKETS; i++) {
                AccountId transitId = AccountId.of(String.format("TRANSIT-%03d", i), "0001", "CLEARING");
                accountRepo.save(new LedgerAccountJpaEntity(
                        transitId.toString(),
                        "Central Clearing Transit Buffer #" + i,
                        AccountType.LIABILITY,
                        "BRL",
                        Money.of("0.00", "BRL").getAmount(),
                        true
                ));
            }

            // Debtor Corporate Checking
            AccountId debtor = AccountId.of("1001-9", "0001", "NEXOR");
            accountRepo.save(new LedgerAccountJpaEntity(
                    debtor.toString(),
                    "Corporate Checking - Debtor",
                    AccountType.LIABILITY,
                    "BRL",
                    Money.of("100000.00", "BRL").getAmount(),
                    false
            ));

            // Creditor Merchant Account
            AccountId creditor = AccountId.of("2002-8", "0001", "NEXOR");
            accountRepo.save(new LedgerAccountJpaEntity(
                    creditor.toString(),
                    "Merchant Receiving - Creditor",
                    AccountType.LIABILITY,
                    "BRL",
                    Money.of("500.00", "BRL").getAmount(),
                    false
            ));
        }
    }

    @Override
    public Optional<LedgerAccount> findAccountById(AccountId accountId) {
        return accountRepo.findById(accountId.toString()).map(this::toDomain);
    }

    @Override
    @Transactional
    public void saveAccount(LedgerAccount account) {
        accountRepo.findById(account.getId().toString())
                .ifPresentOrElse(entity -> {
                    // BUG THIS FIXES: the old code re-fetched "entity" here and copied the
                    // balance onto it unconditionally. Because the fetch happens right before
                    // the write, entity.getVersion() always matched the current DB row at flush
                    // time, so Hibernate's own @Version check could never fire a conflict — it
                    // was comparing the fresh row against itself, not against what "account" was
                    // actually computed from. Two concurrent debits from the same starting
                    // balance would both "succeed", and the second write would silently clobber
                    // the first (lost update) instead of throwing.
                    //
                    // The fix: compare the version this LedgerAccount was originally read at
                    // (account.getVersion()) against the version in the database right now
                    // (entity.getVersion()). If they differ, someone else committed a change to
                    // this account since we read it — reject the write instead of overwriting it.
                    long currentVersion = entity.getVersion() != null ? entity.getVersion() : -1L;
                    if (account.getVersion() >= 0 && account.getVersion() != currentVersion) {
                        throw new ObjectOptimisticLockingFailureException(
                                LedgerAccountJpaEntity.class, account.getId().toString());
                    }
                    entity.setBalance(account.getBalance().getAmount());
                    accountRepo.save(entity);
                }, () -> {
                    accountRepo.save(new LedgerAccountJpaEntity(
                            account.getId().toString(),
                            account.getName(),
                            account.getType(),
                            account.getCurrency(),
                            account.getBalance().getAmount(),
                            account.isAllowOverdraft()
                    ));
                });
    }

    @Override
    @Transactional
    public void saveJournalEntry(JournalEntry entry) {
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity(
                entry.getEntryId(),
                entry.getReferenceId(),
                entry.getTimestamp(),
                entry.getMemo(),
                new java.util.ArrayList<>()
        );

        List<PostingLegJpaEntity> legEntities = entry.getLegs().stream()
                .map(leg -> new PostingLegJpaEntity(
                        entity,
                        leg.accountId().toString(),
                        leg.type(),
                        leg.amount().getAmount(),
                        leg.amount().getCurrencyCode(),
                        leg.description()
                ))
                .toList();

        entity.getLegs().addAll(legEntities);

        journalRepo.save(entity);
    }

    @Override
    public List<JournalEntry> getJournal() {
        return journalRepo.findAll().stream().map(entity -> {
            List<PostingLeg> domainLegs = entity.getLegs().stream().map(leg -> {
                AccountId accId = AccountId.fromString(leg.getAccountId());
                Money amt = Money.of(leg.getAmount(), leg.getCurrency());
                return new PostingLeg(accId, leg.getPostingType(), amt, leg.getDescription());
            }).toList();

            return new JournalEntry(
                    entity.getEntryId(),
                    entity.getReferenceId(),
                    entity.getTimestamp(),
                    entity.getMemo(),
                    domainLegs
            );
        }).toList();
    }

    private LedgerAccount toDomain(LedgerAccountJpaEntity entity) {
        AccountId accountId = AccountId.fromString(entity.getAccountId());
        Money balance = Money.of(entity.getBalance(), entity.getCurrency());
        long version = entity.getVersion() != null ? entity.getVersion() : -1L;
        return new LedgerAccount(
                accountId,
                entity.getAccountName(),
                entity.getAccountType(),
                entity.getCurrency(),
                balance,
                entity.isAllowOverdraft(),
                version
        );
    }
}
