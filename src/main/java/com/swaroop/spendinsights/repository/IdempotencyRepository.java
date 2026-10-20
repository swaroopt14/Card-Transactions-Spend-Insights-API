package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByOwnerEmailAndIdempotencyKey(String ownerEmail, String idempotencyKey);
}
