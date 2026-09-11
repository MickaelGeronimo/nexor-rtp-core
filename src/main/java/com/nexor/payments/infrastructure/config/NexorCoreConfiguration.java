package com.nexor.payments.infrastructure.config;

import com.nexor.payments.application.fraud.FraudScreeningChain;
import com.nexor.payments.application.port.in.SubmitPaymentUseCase;
import com.nexor.payments.application.port.out.*;
import com.nexor.payments.application.routing.SmartRailRouter;
import com.nexor.payments.application.saga.PaymentSagaOrchestrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class NexorCoreConfiguration {

    @Bean
    public FraudScreeningChain fraudScreeningChain() {
        return FraudScreeningChain.createDefault();
    }

    @Bean
    public SmartRailRouter smartRailRouter() {
        return new SmartRailRouter();
    }

    @Bean
    public com.nexor.payments.application.ledger.LedgerIntegrityService ledgerIntegrityService(LedgerRepositoryPort ledgerRepository) {
        return new com.nexor.payments.application.ledger.LedgerIntegrityService(ledgerRepository);
    }

    @Bean
    public SubmitPaymentUseCase submitPaymentUseCase(
            PaymentRepositoryPort paymentRepository,
            LedgerRepositoryPort ledgerRepository,
            ClearingRailPort clearingRailPort,
            EventPublisherPort eventPublisher,
            IdempotencyStoragePort idempotencyStorage,
            FraudScreeningChain fraudChain,
            SmartRailRouter railRouter,
            PaymentTransactionCoordinatorPort transactionCoordinator) {
        return new PaymentSagaOrchestrator(
                paymentRepository,
                ledgerRepository,
                clearingRailPort,
                eventPublisher,
                idempotencyStorage,
                fraudChain,
                railRouter,
                transactionCoordinator
        );
    }
}
