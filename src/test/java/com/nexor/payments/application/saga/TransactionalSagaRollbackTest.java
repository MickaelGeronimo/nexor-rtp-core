package com.nexor.payments.application.saga;

import com.nexor.payments.application.port.out.EventPublisherPort;
import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.application.port.out.PaymentTransactionCoordinatorPort;
import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.ledger.PostingLeg;
import com.nexor.payments.domain.model.*;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataJournalEntryRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataLedgerAccountRepository;
import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository.SpringDataPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@DisplayName("Transactional Boundary & ACID Rollback Tests")
class TransactionalSagaRollbackTest extends com.nexor.payments.testsupport.AbstractContainerizedTest {

    @Autowired
    private LedgerRepositoryPort ledgerRepository;

    @MockBean
    private EventPublisherPort eventPublisher;

    @Autowired
    private PaymentTransactionCoordinatorPort transactionCoordinator;

    @Autowired
    private SpringDataLedgerAccountRepository accountRepo;

    @Autowired
    private SpringDataJournalEntryRepository journalRepo;

    @Autowired
    private SpringDataPaymentRepository paymentRepo;

    @Test
    @DisplayName("Should rollback debtor balance and journal entry when outbox persistence fails within Unit of Work")
    void shouldRollbackAllWhenStepFailsInAtomicReservation() {
        AccountId debtorId = AccountId.of("1001-9", "0001", "NEXOR");
        LedgerAccount debtor = ledgerRepository.findAccountById(debtorId).orElseThrow();
        LedgerAccount transit = ledgerRepository.findAccountById(PaymentSagaOrchestrator.SETTLEMENT_TRANSIT_ACCOUNT).orElseThrow();

        Money originalDebtorBalance = debtor.getBalance();
        Money originalTransitBalance = transit.getBalance();
        long initialJournalCount = journalRepo.count();

        // Simulate an unrecoverable failure during the outbox event dispatch in the transaction
        doThrow(new RuntimeException("Simulated Outbox DB failure during transaction commit"))
                .when(eventPublisher).publishOutboxEvent(any(), any(), any(), any());

        PaymentInstruction instruction = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                debtorId,
                AccountId.of("2002-8", "0001", "NEXOR"),
                Money.of("5000.00", "BRL"),
                PaymentRail.PIX,
                "ACID rollback test"
        );
        instruction.markValidated();
        instruction.markFraudApproved();
        instruction.markFundsReserved();

        PostingLeg leg1 = PostingLeg.debit(debtor.getId(), instruction.getAmount(), "Hold Leg 1");
        PostingLeg leg2 = PostingLeg.credit(transit.getId(), instruction.getAmount(), "Hold Leg 2");
        JournalEntry holdEntry = new JournalEntry(instruction.getTransactionId().value(), "ACID Hold", List.of(leg1, leg2));

        debtor.applyLeg(leg1);
        transit.applyLeg(leg2);

        // Attempt atomic reservation via the transactional Spring proxy -> triggers rollback!
        assertThatThrownBy(() -> transactionCoordinator.executeAtomicReservation(
                instruction,
                debtor,
                transit,
                holdEntry,
                Map.of("test", "payload")
        )).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Simulated Outbox DB failure");

        // Verify that database state was completely protected by ACID rollback:
        LedgerAccount debtorAfterRollback = ledgerRepository.findAccountById(debtorId).orElseThrow();
        LedgerAccount transitAfterRollback = ledgerRepository.findAccountById(PaymentSagaOrchestrator.SETTLEMENT_TRANSIT_ACCOUNT).orElseThrow();

        assertThat(debtorAfterRollback.getBalance()).isEqualTo(originalDebtorBalance);
        assertThat(transitAfterRollback.getBalance()).isEqualTo(originalTransitBalance);
        assertThat(journalRepo.count()).isEqualTo(initialJournalCount);
        assertThat(paymentRepo.findById(instruction.getTransactionId().value())).isEmpty();
    }
}
