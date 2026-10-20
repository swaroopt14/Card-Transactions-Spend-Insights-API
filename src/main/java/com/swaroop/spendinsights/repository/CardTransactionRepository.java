package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.CardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    Page<CardTransaction> findByCardIdOrderByOccurredAtDesc(Long cardId, Pageable pageable);

    long countByCardIdAndOccurredAtBetween(Long cardId, Instant from, Instant to);

    List<CardTransaction> findByFlaggedTrueOrderByOccurredAtDesc();

    @Query("""
           select new com.swaroop.spendinsights.repository.CategorySpend(t.category, sum(t.amount), count(t))
           from CardTransaction t
           where t.card.owner.email = :email and t.occurredAt >= :from and t.occurredAt < :to
           group by t.category
           order by sum(t.amount) desc
           """)
    List<CategorySpend> monthlySpendByCategory(@Param("email") String email,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);
}
