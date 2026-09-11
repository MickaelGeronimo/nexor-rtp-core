package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.repository;

import com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity.LedgerAccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataLedgerAccountRepository extends JpaRepository<LedgerAccountJpaEntity, String> {
}
