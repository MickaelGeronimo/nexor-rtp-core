package com.nexor.payments.domain.model;

import com.nexor.payments.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment Instruction State Machine Tests")
class PaymentInstructionStateMachineTest {

    @Test
    @DisplayName("Should transition cleanly through entire successful lifecycle")
    void shouldFollowValidLifecycle() {
        PaymentInstruction instruction = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                AccountId.simple("ACC-1"),
                AccountId.simple("ACC-2"),
                Money.of("50.00", "BRL"),
                PaymentRail.PIX,
                "Pix transfer"
        );

        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.INITIATED);

        instruction.markValidated();
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.VALIDATED);

        instruction.markFraudApproved();
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.FRAUD_APPROVED);

        instruction.markFundsReserved();
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.FUNDS_RESERVED);

        instruction.markClearingSubmitted("BACEN-MSG-001");
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.CLEARING_SUBMITTED);
        assertThat(instruction.getClearingReference()).isEqualTo("BACEN-MSG-001");

        instruction.markSettled();
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(instruction.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Should reject illegal state skipping (e.g. INITIATED directly to SETTLED)")
    void shouldRejectIllegalStateSkip() {
        PaymentInstruction instruction = new PaymentInstruction(
                TransactionId.generate(),
                EndToEndId.generate("E"),
                AccountId.simple("ACC-1"),
                AccountId.simple("ACC-2"),
                Money.of("50.00", "BRL"),
                PaymentRail.PIX,
                "Pix transfer"
        );

        assertThatThrownBy(instruction::markSettled)
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Illegal state transition from INITIATED to SETTLED");
    }
}
