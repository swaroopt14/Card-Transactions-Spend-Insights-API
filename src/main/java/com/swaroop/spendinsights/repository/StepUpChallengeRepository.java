package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.StepUpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StepUpChallengeRepository extends JpaRepository<StepUpChallenge, Long> {
    Optional<StepUpChallenge> findByIdAndOwnerEmailAndCardId(Long id, String ownerEmail, Long cardId);

    Optional<StepUpChallenge> findFirstByOwnerEmailAndIdempotencyKeyAndStatus(
            String ownerEmail, String idempotencyKey, String status);
}
