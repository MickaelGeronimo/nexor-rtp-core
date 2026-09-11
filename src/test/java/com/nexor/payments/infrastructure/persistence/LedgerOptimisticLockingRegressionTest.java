package com.nexor.payments.infrastructure.persistence;

import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.ledger.PostingLeg;
import com.nexor.payments.domain.model.AccountId;
import com.nexor.payments.domain.model.Money;
import com.nexor.payments.testsupport.AbstractContainerizedTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for a lost-update bug found in {@code JpaLedgerRepositoryAdapter#saveAccount}.
 *
 * <p><b>The bug:</b> {@code saveAccount} used to re-fetch the account row immediately before
 * writing and copy the caller's balance onto that freshly-fetched entity. Because the fetch and
 * the write happened back to back, the entity's {@code @Version} always matched the current row
 * at flush time — Hibernate's optimistic lock was effectively comparing the row against itself,
 * never against the version the caller actually read when it computed the new balance. Two
 * concurrent debits starting from the same balance would both "succeed", and whichever wrote
 * second would silently overwrite the first (a lost update) instead of the second being rejected.
 *
 * <p>This mattered specifically because ADR-008 documents optimistic locking as the mechanism
 * that is supposed to prevent exactly this ("If two transactions read the same version and both
 * attempt an UPDATE, the second will throw {@code OptimisticLockException}") — the code did not
 * actually deliver on that.
 *
 * <p><b>The fix:</b> {@link LedgerAccount} now carries the version it was read at
 * ({@link LedgerAccount#getVersion()}), and {@code saveAccount} compares it against the
 * database's current version before writing, throwing {@link OptimisticLockingFailureException}
 * on a mismatch instead of overwriting.
 */
@SpringBootTest
@DisplayName("Ledger Optimistic Locking — Lost Update Regression Test")
class LedgerOptimisticLockingRegressionTest extends AbstractContainerizedTest {

    @Autowired
    private LedgerRepositoryPort ledgerRepository;

    private final AccountId debtorId = AccountId.of("1001-9", "0001", "NEXOR");

    @Test
    @DisplayName("A stale write (based on an outdated read) is rejected instead of silently overwriting a concurrent update")
    void staleWriteIsRejectedNotSilentlyApplied() {
        // Two "transactions" read the same account at the same version — simulating two
        // concurrent requests that both computed a debit from the same starting balance.
        LedgerAccount readByTransactionA = ledgerRepository.findAccountById(debtorId).orElseThrow();
        LedgerAccount readByTransactionB = ledgerRepository.findAccountById(debtorId).orElseThrow();
        Money startingBalance = readByTransactionA.getBalance();

        // Transaction A applies its debit and saves first — this must succeed and bump the
        // account's version in the database.
        readByTransactionA.applyLeg(PostingLeg.debit(debtorId, Money.of("100.00", "BRL"), "tx-A debit"));
        ledgerRepository.saveAccount(readByTransactionA);

        // Transaction B applies its own debit on top of the SAME stale starting balance it read
        // earlier, then tries to save. Before the fix, this would silently overwrite tx-A's
        // update. After the fix, it must be rejected.
        readByTransactionB.applyLeg(PostingLeg.debit(debtorId, Money.of("50.00", "BRL"), "tx-B stale debit"));

        assertThatThrownBy(() -> ledgerRepository.saveAccount(readByTransactionB))
                .as("saving a LedgerAccount whose version is behind the current DB row must be rejected")
                .isInstanceOf(OptimisticLockingFailureException.class);

        // The database must reflect ONLY transaction A's debit (100.00) — not both (150.00),
        // and not transaction B's alone (50.00) silently replacing A's.
        LedgerAccount finalState = ledgerRepository.findAccountById(debtorId).orElseThrow();
        assertThat(finalState.getBalance()).isEqualTo(startingBalance.subtract(Money.of("100.00", "BRL")));
    }

    @Test
    @DisplayName("Two THREADS racing in real time on the same account: exactly one debit lands, the other is rejected — not a sequential stale-write, an actual concurrent race")
    void concurrentThreadsRacingOnSameAccount_exactlyOneSucceeds() throws Exception {
        Money startingBalance = ledgerRepository.findAccountById(debtorId).orElseThrow().getBalance();
        Money debitA = Money.of("100.00", "BRL");
        Money debitB = Money.of("50.00", "BRL");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        // Both threads must finish their READ before either is allowed to WRITE — otherwise the
        // JVM could just happen to run them one fully after the other, which is the sequential
        // scenario the other test already covers, not a genuine race.
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        Future<Boolean> resultA = executor.submit(() -> attemptConcurrentDebit(debitA, bothHaveRead));
        Future<Boolean> resultB = executor.submit(() -> attemptConcurrentDebit(debitB, bothHaveRead));

        boolean succeededA = resultA.get(15, TimeUnit.SECONDS);
        boolean succeededB = resultB.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(List.of(succeededA, succeededB))
                .as("exactly one of the two concurrent debits must succeed, the other must be rejected")
                .containsExactlyInAnyOrder(true, false);

        LedgerAccount finalState = ledgerRepository.findAccountById(debtorId).orElseThrow();
        Money expectedIfADebited = startingBalance.subtract(debitA);
        Money expectedIfBDebited = startingBalance.subtract(debitB);

        assertThat(finalState.getBalance())
                .as("final balance must reflect exactly the ONE debit that won the race — never both, never neither")
                .isIn(expectedIfADebited, expectedIfBDebited);
    }

    private boolean attemptConcurrentDebit(Money amount, CyclicBarrier bothHaveRead) throws Exception {
        LedgerAccount account = ledgerRepository.findAccountById(debtorId).orElseThrow();
        bothHaveRead.await(10, TimeUnit.SECONDS);
        account.applyLeg(PostingLeg.debit(debtorId, amount, "concurrent race debit"));
        try {
            ledgerRepository.saveAccount(account);
            return true;
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
    }
}
