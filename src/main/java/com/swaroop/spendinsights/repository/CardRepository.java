package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.Card;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {
    List<Card> findByOwnerEmail(String email);
    Optional<Card> findByIdAndOwnerEmail(Long id, String email);

    /** Row lock so two transactions on the same card cannot both pass the velocity check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> findByIdForUpdate(@Param("id") Long id);
}
