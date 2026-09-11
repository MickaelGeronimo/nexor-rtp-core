package com.nexor.payments.infrastructure.adapter.in.web;

import com.nexor.payments.application.port.in.PaymentResponseDto;
import com.nexor.payments.application.port.in.SubmitPaymentCommand;
import com.nexor.payments.application.port.in.SubmitPaymentUseCase;
import com.nexor.payments.application.port.out.LedgerRepositoryPort;
import com.nexor.payments.application.port.out.PaymentRepositoryPort;
import com.nexor.payments.domain.ledger.JournalEntry;
import com.nexor.payments.domain.ledger.LedgerAccount;
import com.nexor.payments.domain.model.*;
import com.nexor.payments.infrastructure.adapter.in.web.dto.PaymentRequestDto;
import com.nexor.payments.infrastructure.observability.PaymentMetrics;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final SubmitPaymentUseCase submitPaymentUseCase;
    private final PaymentRepositoryPort paymentRepository;
    private final LedgerRepositoryPort ledgerRepository;
    private final com.nexor.payments.application.ledger.LedgerIntegrityService ledgerIntegrityService;
    private final PaymentMetrics paymentMetrics;

    public PaymentController(
            SubmitPaymentUseCase submitPaymentUseCase,
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            com.nexor.payments.application.ledger.LedgerIntegrityService ledgerIntegrityService,
            PaymentMetrics paymentMetrics) {
        this.submitPaymentUseCase = submitPaymentUseCase;
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerIntegrityService = ledgerIntegrityService;
        this.paymentMetrics = paymentMetrics;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponseDto> submitPayment(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody PaymentRequestDto request) {

        Instant start = Instant.now();

        AccountId debtor = parseAccountId(request.debtorAccount());
        AccountId creditor = parseAccountId(request.creditorAccount());
        Money amount = Money.of(request.amount(), request.currency());

        SubmitPaymentCommand command = new SubmitPaymentCommand(
                idempotencyKey,
                debtor,
                creditor,
                amount,
                request.remittanceInformation(),
                request.rail()
        );

        PaymentResponseDto response = submitPaymentUseCase.submitPayment(command);

        Duration duration = Duration.between(start, Instant.now());
        paymentMetrics.recordExecutionTime(duration);
        paymentMetrics.recordPaymentProcessed(response.rail(), response.status());

        if (response.status() == PaymentStatus.COMPENSATED) {
            paymentMetrics.recordCompensationTriggered(response.rail());
        }

        HttpStatus status = response.status() == PaymentStatus.SETTLED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/payments/{txId}")
    public ResponseEntity<?> getPayment(@PathVariable String txId) {
        return paymentRepository.findById(new TransactionId(txId))
                .map(inst -> ResponseEntity.ok(Map.of(
                        "transactionId", inst.getTransactionId().value(),
                        "endToEndId", inst.getEndToEndId().value(),
                        "status", inst.getStatus(),
                        "rail", inst.getRail(),
                        "amount", inst.getAmount().toString(),
                        "debtor", inst.getDebtorAccountId().toString(),
                        "creditor", inst.getCreditorAccountId().toString(),
                        "clearingRef", inst.getClearingReference() != null ? inst.getClearingReference() : "N/A",
                        "failureReason", inst.getFailureReason() != null ? inst.getFailureReason() : "N/A"
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/ledger/accounts/{number}")
    public ResponseEntity<?> getLedgerAccount(@PathVariable String number) {
        AccountId id = parseAccountId(number);
        return ledgerRepository.findAccountById(id)
                .map(acc -> ResponseEntity.ok(Map.of(
                        "accountId", acc.getId().toString(),
                        "name", acc.getName(),
                        "type", acc.getType(),
                        "currency", acc.getCurrency(),
                        "balance", acc.getBalance().toString()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/ledger/accounts/{number}/integrity")
    public ResponseEntity<?> verifyLedgerIntegrity(@PathVariable String number) {
        AccountId id = parseAccountId(number);
        return ledgerRepository.findAccountById(id)
                .map(acc -> {
                    // Corporate checking starts with 100k seed, others 0
                    Money seed = id.number().equals("1001-9") ? Money.of("100000.00", acc.getCurrency()) : Money.zero(acc.getCurrency());
                    var report = ledgerIntegrityService.verifyAccountIntegrity(id, seed);
                    return ResponseEntity.ok(Map.of(
                            "accountId", report.accountId().toString(),
                            "status", report.status(),
                            "materializedBalance", report.materializedBalance().toString(),
                            "recalculatedFromJournals", report.recalculatedBalance().toString(),
                            "discrepancy", report.discrepancy().toString(),
                            "totalJournalLegsAudited", report.totalLegsAudited(),
                            "auditedAt", report.auditedAt()
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/ledger/journal")
    public ResponseEntity<List<JournalEntry>> getJournal() {
        return ResponseEntity.ok(ledgerRepository.getJournal());
    }

    private AccountId parseAccountId(String raw) {
        if (raw.contains(":")) {
            String[] parts = raw.split(":");
            if (parts.length == 3) {
                return AccountId.of(parts[2], parts[1], parts[0]);
            }
            if (parts.length == 2) {
                return AccountId.of(parts[1], "0001", parts[0]);
            }
        }
        return AccountId.simple(raw);
    }
}
