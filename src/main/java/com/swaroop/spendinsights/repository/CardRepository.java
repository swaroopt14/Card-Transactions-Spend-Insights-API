package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {
    List<Card> findByOwnerEmail(String email);
    Optional<Card> findByIdAndOwnerEmail(Long id, String email);
}
