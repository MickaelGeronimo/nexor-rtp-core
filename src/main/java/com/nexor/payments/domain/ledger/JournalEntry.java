package com.nexor.payments.domain.ledger;

import com.nexor.payments.domain.exception.LedgerImbalanceException;
import com.nexor.payments.domain.model.Money;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable double-entry journal entry.
 * Enforces the core accounting invariant: sum(Debits) == sum(Credits).
 */
public final class JournalEntry {

    private final String entryId;
    private final String referenceId;
    private final Instant timestamp;
    private final List<PostingLeg> legs;
    private final String memo;

    public JournalEntry(String referenceId, String memo, List<PostingLeg> legs) {
        this(UUID.randomUUID().toString(), referenceId, Instant.now(), memo, legs);
    }

    public JournalEntry(String entryId, String referenceId, Instant timestamp, String memo, List<PostingLeg> legs) {
        this.entryId = Objects.requireNonNull(entryId, "entryId cannot be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.memo = Objects.requireNonNull(memo, "memo cannot be null");
        Objects.requireNonNull(legs, "legs cannot be null");

        if (legs.size() < 2) {
            throw new IllegalArgumentException("A double-entry journal transaction requires at least 2 legs (1 debit, 1 credit)");
        }

        this.legs = List.copyOf(legs);
        verifyDoubleEntryInvariant();
    }

    private void verifyDoubleEntryInvariant() {
        String baseCurrency = legs.get(0).amount().getCurrencyCode();
        Money totalDebit = Money.zero(baseCurrency);
        Money totalCredit = Money.zero(baseCurrency);

        for (PostingLeg leg : legs) {
            if (!leg.amount().getCurrencyCode().equals(baseCurrency)) {
                throw new IllegalArgumentException("Multi-currency journal entries require exchange legs. Mismatched: " 
                        + leg.amount().getCurrencyCode() + " vs " + baseCurrency);
            }

            if (leg.type() == PostingType.DEBIT) {
                totalDebit = totalDebit.add(leg.amount());
            } else {
                totalCredit = totalCredit.add(leg.amount());
            }
        }

        if (!totalDebit.equals(totalCredit)) {
            throw new LedgerImbalanceException(String.format(
                    "Accounting equation broken in JournalEntry [%s] for ref [%s]! Total Debits (%s) != Total Credits (%s)",
                    entryId, referenceId, totalDebit, totalCredit));
        }
    }

    public String getEntryId() {
        return entryId;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public List<PostingLeg> getLegs() {
        return legs;
    }

    public String getMemo() {
        return memo;
    }
}
