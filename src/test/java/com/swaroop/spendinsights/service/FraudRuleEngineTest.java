package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudRuleEngineTest {
    @Mock CardTransactionRepository txnRepo;
    FraudRuleEngine engine;
    Card card;
    Instant at = Instant.parse("2026-10-10T10:00:00Z");

    @BeforeEach
    void setUp() {
        engine = new FraudRuleEngine(txnRepo, new BigDecimal("50000"), 5, 60);
        card = new Card(new AppUser("a@b.com", "h", Role.USER), "1234", "Main", new BigDecimal("40000"));
        ReflectionTestUtils.setField(card, "id", 1L);
    }

    @Test
    void cleanTransactionHasNoFlags() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        assertThat(engine.evaluate(card, new BigDecimal("499"), at)).isEmpty();
    }

    @Test
    void flagsHighAmountOverLimitAndVelocity() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(5L);
        assertThat(engine.evaluate(card, new BigDecimal("60000"), at))
                .containsExactly("HIGH_AMOUNT", "OVER_LIMIT", "VELOCITY");
    }

    @Test
    void highAmountIsExclusiveAtTheThreshold() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        Card roomy = cardWithLimit("100000");
        assertThat(engine.evaluate(roomy, new BigDecimal("50000.00"), at)).isEmpty();
        assertThat(engine.evaluate(roomy, new BigDecimal("50000.01"), at)).containsExactly("HIGH_AMOUNT");
    }

    @Test
    void overLimitIsIndependentOfHighAmount() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        assertThat(engine.evaluate(card, new BigDecimal("40000.00"), at)).isEmpty();
        assertThat(engine.evaluate(card, new BigDecimal("40000.01"), at)).containsExactly("OVER_LIMIT");
    }

    @Test
    void velocityTriggersAtThresholdAndNotBelow() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(4L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at)).isEmpty();
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(5L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at)).containsExactly("VELOCITY");
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(6L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at)).containsExactly("VELOCITY");
    }

    @Test
    void velocityWindowIsInclusiveAndOneMinuteLong() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        engine.evaluate(card, new BigDecimal("10"), at);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(txnRepo).countByCardIdAndOccurredAtBetween(eq(1L), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(at.minus(60, ChronoUnit.SECONDS));
        assertThat(to.getValue()).isEqualTo(at);
    }

    private Card cardWithLimit(String limit) {
        Card roomy = new Card(new AppUser("a@b.com", "h", Role.USER), "1234", "Main", new BigDecimal(limit));
        ReflectionTestUtils.setField(roomy, "id", 1L);
        return roomy;
    }
}
