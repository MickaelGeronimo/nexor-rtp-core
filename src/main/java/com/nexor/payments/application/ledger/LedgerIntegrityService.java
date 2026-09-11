package com.nexor.payments.application.ledger;

import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.domain.ledger.AccountType;
import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.ledger.PostingLeg;
import com.nexor.payments.domain.ledger.PostingType;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Continuous Ledger Integrity Verification Engine.
 * Recalculates account balances from scratch using raw, immutable Journal Entries & Posting Legs,
 * comparing the reconstructed balance against the materialized account balance.
 * Detects silent balance tampering, double-spend anomalies, and accounting drift.
 */
public class LedgerIntegrityService {

    private final LedgerRepositoryPort ledgerRepository;

    public LedgerIntegrityService(LedgerRepositoryPort ledgerRepository) {
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository cannot be null");
    }

    public record IntegrityReport(
            AccountId accountId,
            Status status,
            Money materializedBalance,
            Money recalculatedBalance,
            Money discrepancy,
            int totalLegsAudited,
            Instant auditedAt
    ) {
        public enum Status {
            MATCH,
            CORRUPTED
        }
    }

    public IntegrityReport verifyAccountIntegrity(AccountId accountId, Money baselineBalance) {
        LedgerAccount account = ledgerRepository.findAccountById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        List<JournalEntry> journal = ledgerRepository.getJournal();
        String currency = account.getCurrency();

        Money computedBalance = baselineBalance != null ? baselineBalance : Money.zero(currency);
        int legsCount = 0;

        for (JournalEntry entry : journal) {
            for (PostingLeg leg : entry.getLegs()) {
                if (leg.accountId().equals(accountId)) {
                    legsCount++;
                    computedBalance = applyLegAccounting(account.getType(), computedBalance, leg);
                }
            }
        }

        Money currentBalance = account.getBalance();
        boolean isMatch = computedBalance.equals(currentBalance);
        Money discrepancy = isMatch ? Money.zero(currency) : currentBalance.subtract(computedBalance);

        return new IntegrityReport(
                accountId,
                isMatch ? IntegrityReport.Status.MATCH : IntegrityReport.Status.CORRUPTED,
                currentBalance,
                computedBalance,
                discrepancy,
                legsCount,
                Instant.now()
        );
    }

    private Money applyLegAccounting(AccountType type, Money current, PostingLeg leg) {
        Money amount = leg.amount();
        return switch (type) {
            case ASSET, EXPENSE -> leg.type() == PostingType.DEBIT ? current.add(amount) : current.subtract(amount);
            case LIABILITY, EQUITY, REVENUE -> leg.type() == PostingType.CREDIT ? current.add(amount) : current.subtract(amount);
        };
    }
}
