package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.BehavioralSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BehavioralSignalRepository extends JpaRepository<BehavioralSignal, Long> {
    Optional<BehavioralSignal> findByTransactionId(Long transactionId);

    Optional<BehavioralSignal> findByChallengeId(Long challengeId);
}
