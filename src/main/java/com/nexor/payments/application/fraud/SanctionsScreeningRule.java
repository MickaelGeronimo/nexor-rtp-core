package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.PaymentInstruction;

import java.util.Set;

/**
 * Validates debtor and creditor accounts against simulated OFAC / PEP / Bacen sanctions watchlists.
 */
public class SanctionsScreeningRule implements FraudRule {

    private final Set<String> sanctionedAccounts = Set.of(
            "SANCTIONED-999",
            "OFAC-BLOCKED-001",
            "TERROR-SUSPECT-07"
    );

    @Override
    public String getRuleName() {
        return "SANCTIONS_AND_PEP_SCREENING";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public FraudEvaluationResult evaluate(PaymentInstruction instruction) {
        String debtorNum = instruction.getDebtorAccountId().number();
        String creditorNum = instruction.getCreditorAccountId().number();

        if (sanctionedAccounts.contains(debtorNum)) {
            return FraudEvaluationResult.reject(getRuleName(), "SANCTION_DEBTOR_BLOCKED",
                    "Debtor account is on the international sanctions list");
        }

        if (sanctionedAccounts.contains(creditorNum)) {
            return FraudEvaluationResult.reject(getRuleName(), "SANCTION_CREDITOR_BLOCKED",
                    "Creditor account is on the international sanctions list");
        }

        return FraudEvaluationResult.approve(getRuleName());
    }
}
