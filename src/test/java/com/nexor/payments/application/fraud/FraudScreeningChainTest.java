package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AML & Fraud Screening Rule Chain Tests")
class FraudScreeningChainTest {

    private FraudScreeningChain fraudChain;

    @BeforeEach
    void setUp() {
        fraudChain = FraudScreeningChain.createDefault();
    }

    @Test
    @DisplayName("Should approve normal legitimate transaction")
    void shouldApproveNormalPayment() {
        PaymentInstruction payment = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                AccountId.of("1001-9", "0001", "NEXOR"),
                AccountId.of("2002-8", "0001", "NEXOR"),
                Money.of("500.00", "BRL"),
                PaymentRail.PIX,
                "Regular payment"
        );

        FraudEvaluationResult result = fraudChain.evaluate(payment);
        assertThat(result.status()).isEqualTo(FraudEvaluationResult.Status.APPROVED);
    }

    @Test
    @DisplayName("Should reject payment involving sanctioned debtor account")
    void shouldRejectSanctionedDebtor() {
        PaymentInstruction payment = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                AccountId.of("SANCTIONED-999", "0001", "BADBANK"),
                AccountId.of("2002-8", "0001", "NEXOR"),
                Money.of("100.00", "BRL"),
                PaymentRail.PIX,
                "Suspicious transfer"
        );

        FraudEvaluationResult result = fraudChain.evaluate(payment);
        assertThat(result.status()).isEqualTo(FraudEvaluationResult.Status.REJECTED);
        assertThat(result.ruleName()).isEqualTo("SANCTIONS_AND_PEP_SCREENING");
        assertThat(result.reasonCode()).isEqualTo("SANCTION_DEBTOR_BLOCKED");
    }

    @Test
    @DisplayName("Should reject transaction exceeding instant rail maximum threshold")
    void shouldRejectHighValueExceedingThreshold() {
        PaymentInstruction payment = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                AccountId.of("1001-9", "0001", "NEXOR"),
                AccountId.of("2002-8", "0001", "NEXOR"),
                Money.of("1500000.00", "BRL"), // 1.5 Million BRL (limit is 1.0M)
                PaymentRail.PIX,
                "Mega transfer"
        );

        FraudEvaluationResult result = fraudChain.evaluate(payment);
        assertThat(result.status()).isEqualTo(FraudEvaluationResult.Status.REJECTED);
        assertThat(result.ruleName()).isEqualTo("HIGH_VALUE_TRANSACTION_LIMIT");
        assertThat(result.reasonCode()).isEqualTo("LIMIT_EXCEEDED");
    }
}
