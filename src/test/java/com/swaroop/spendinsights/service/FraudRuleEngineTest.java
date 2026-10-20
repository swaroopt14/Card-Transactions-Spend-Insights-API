package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudRuleEngineTest {
    @Mock CardTransactionRepository txnRepo;
    FraudRuleEngine engine;
    Card card;

    @BeforeEach
    void setUp() {
        engine = new FraudRuleEngine(txnRepo, new BigDecimal("50000"), 5);
        card = new Card(new AppUser("a@b.com", "h", Role.USER), "1234", "Main", new BigDecimal("40000"));
        ReflectionTestUtils.setField(card, "id", 1L);
    }

    @Test
    void cleanTransactionHasNoFlags() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), any(), any())).thenReturn(0L);
        assertThat(engine.evaluate(card, new BigDecimal("499"), Instant.now())).isEmpty();
    }

    @Test
    void flagsHighAmountOverLimitAndVelocity() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), any(), any())).thenReturn(5L);
        assertThat(engine.evaluate(card, new BigDecimal("60000"), Instant.now()))
                .containsExactly("HIGH_AMOUNT", "OVER_LIMIT", "VELOCITY");
    }
}
