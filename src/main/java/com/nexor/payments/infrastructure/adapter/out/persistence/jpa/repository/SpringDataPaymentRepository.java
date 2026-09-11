package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.PaymentInstructionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataPaymentRepository extends JpaRepository<PaymentInstructionJpaEntity, String> {
    Optional<PaymentInstructionJpaEntity> findByEndToEndId(String endToEndId);
}
