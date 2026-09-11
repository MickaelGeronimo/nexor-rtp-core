package com.nexor.payments.application.fraud;

import com.nexor.payments.domain.model.PaymentInstruction;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory velocity rule monitoring burst frequency per debtor account within a sliding window.
 */
public class VelocityCheckRule implements FraudRule {

    private final int maxTransactionsPerMinute;
    private final Map<String, AccountVelocity> velocityStore = new ConcurrentHashMap<>();

    public VelocityCheckRule() {
        this(10_000); // default 10000 tx/min
    }

    public VelocityCheckRule(int maxTransactionsPerMinute) {
        this.maxTransactionsPerMinute = maxTransactionsPerMinute;
    }

    @Override
    public String getRuleName() {
        return "VELOCITY_BURST_CHECK";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public FraudEvaluationResult evaluate(PaymentInstruction instruction) {
        String accountKey = instruction.getDebtorAccountId().toString();
        long currentEpochMinute = Instant.now().getEpochSecond() / 60;

        AccountVelocity velocity = velocityStore.compute(accountKey, (k, v) -> {
            if (v == null || v.epochMinute != currentEpochMinute) {
                return new AccountVelocity(currentEpochMinute, new AtomicInteger(1));
            }
            v.counter.incrementAndGet();
            return v;
        });

        if (velocity.counter.get() > maxTransactionsPerMinute) {
            return FraudEvaluationResult.reject(getRuleName(), "VELOCITY_LIMIT_EXCEEDED",
                    String.format("Account %s exceeded velocity limit of %d transactions/min",
                            accountKey, maxTransactionsPerMinute));
        }

        return FraudEvaluationResult.approve(getRuleName());
    }

    private record AccountVelocity(long epochMinute, AtomicInteger counter) {}
}
