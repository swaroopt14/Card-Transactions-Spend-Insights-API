package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.CardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    Page<CardTransaction> findByCardIdOrderByOccurredAtDesc(Long cardId, Pageable pageable);

    @Query("""
           select t from CardTransaction t
           where t.card.id = :cardId and t.occurredAt <= :at
           order by t.occurredAt desc, t.id desc
           """)
    List<CardTransaction> findAtOrBefore(@Param("cardId") Long cardId, @Param("at") Instant at, Pageable pageable);

    long countByCardIdAndOccurredAtBetween(Long cardId, Instant from, Instant to);

    @Query("select coalesce(sum(t.amount), 0) from CardTransaction t where t.card.id = :cardId")
    BigDecimal sumAmountByCardId(@Param("cardId") Long cardId);

    @Query("""
           select t.location from CardTransaction t
           where t.card.id = :cardId and t.location is not null
             and t.occurredAt >= :from and t.occurredAt <= :to
           """)
    List<String> findLocationsInWindow(@Param("cardId") Long cardId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    @Query("""
           select count(t) from CardTransaction t
           where t.card.id = :cardId and t.flagged = true
             and t.occurredAt >= :from and t.occurredAt <= :to
           """)
    long countFlaggedInWindow(@Param("cardId") Long cardId,
                              @Param("from") Instant from,
                              @Param("to") Instant to);

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
