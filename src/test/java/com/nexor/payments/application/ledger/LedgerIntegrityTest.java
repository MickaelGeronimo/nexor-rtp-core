package com.nexor.payments.application.ledger;

import com.nexor.payments.domain.ledger.*;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.infrastructure.adapter.out.persistence.InMemoryLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ledger Integrity Checker Tests")
class LedgerIntegrityTest {

    private InMemoryLedgerRepository ledgerRepository;
    private LedgerIntegrityService integrityService;

    private final AccountId debtorAccount = AccountId.of("1001-9", "0001", "NEXOR");
    private final AccountId transitAccount = AccountId.of("TRANSIT-001", "0001", "CLEARING");

    @BeforeEach
    void setUp() {
        ledgerRepository = new InMemoryLedgerRepository();
        integrityService = new LedgerIntegrityService(ledgerRepository);
    }

    @Test
    @DisplayName("Should report MATCH when materialized balance exactly matches journal leg history")
    void shouldReportMatchOnCleanLedger() {
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorAccount).orElseThrow();
        LedgerAccount transit = ledgerRepository.findAccountById(transitAccount).orElseThrow();

        // Perform 2 valid journal transactions
        PostingLeg leg1 = PostingLeg.debit(debtorAccount, Money.of("500.00", "BRL"), "Tx 1");
        PostingLeg leg2 = PostingLeg.credit(transitAccount, Money.of("500.00", "BRL"), "Tx 1");
        debtor.applyLeg(leg1);
        transit.applyLeg(leg2);
        ledgerRepository.saveJournalEntry(new JournalEntry("REF-1", "Tx 1", List.of(leg1, leg2)));

        PostingLeg leg3 = PostingLeg.debit(debtorAccount, Money.of("250.00", "BRL"), "Tx 2");
        PostingLeg leg4 = PostingLeg.credit(transitAccount, Money.of("250.00", "BRL"), "Tx 2");
        debtor.applyLeg(leg3);
        transit.applyLeg(leg4);
        ledgerRepository.saveJournalEntry(new JournalEntry("REF-2", "Tx 2", List.of(leg3, leg4)));

        ledgerRepository.saveAccount(debtor);
        ledgerRepository.saveAccount(transit);

        // Baseline for debtor was 100,000.00 BRL
        var report = integrityService.verifyAccountIntegrity(debtorAccount, Money.of("100000.00", "BRL"));

        assertThat(report.status()).isEqualTo(LedgerIntegrityService.IntegrityReport.Status.MATCH);
        assertThat(report.materializedBalance()).isEqualTo(Money.of("99250.00", "BRL"));
        assertThat(report.recalculatedBalance()).isEqualTo(Money.of("99250.00", "BRL"));
        assertThat(report.discrepancy().isZero()).isTrue();
        assertThat(report.totalLegsAudited()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should detect CORRUPTED when an unauthorized phantom balance mutation occurs")
    void shouldDetectCorruptionWhenBalanceIsTampered() {
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorAccount).orElseThrow();

        // Tamper with account balance without creating a journal entry (phantom money)
        PostingLeg unauthorizedLeg = PostingLeg.credit(debtorAccount, Money.of("50000.00", "BRL"), "Phantom money injection");
        debtor.applyLeg(unauthorizedLeg);
        ledgerRepository.saveAccount(debtor);

        var report = integrityService.verifyAccountIntegrity(debtorAccount, Money.of("100000.00", "BRL"));

        assertThat(report.status()).isEqualTo(LedgerIntegrityService.IntegrityReport.Status.CORRUPTED);
        assertThat(report.discrepancy()).isEqualTo(Money.of("50000.00", "BRL"));
    }
}
