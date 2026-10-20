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
import java.util.List;
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
        engine = new FraudRuleEngine(txnRepo, new BigDecimal("50000"), 5, 60, 60, 72,
                new BigDecimal("0.20"), new BigDecimal("5000"), "ENTERTAINMENT,OTHER");
        card = new Card(new AppUser("a@b.com", "h", Role.USER), "1234", "Main", new BigDecimal("40000"));
        ReflectionTestUtils.setField(card, "id", 1L);
    }

    @Test
    void cleanTransactionHasNoFlags() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        assertThat(engine.evaluate(card, new BigDecimal("499"), at).reasons()).isEmpty();
        assertThat(engine.evaluate(card, new BigDecimal("499"), at).riskScore()).isZero();
    }

    @Test
    void flagsHighAmountOverLimitAndVelocity() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(5L);
        assertThat(engine.evaluate(card, new BigDecimal("60000"), at).reasons())
                .containsExactly("HIGH_AMOUNT", "OVER_LIMIT", "OVER_BALANCE", "VELOCITY");
    }

    @Test
    void highAmountIsExclusiveAtTheThreshold() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        Card roomy = cardWithLimit("100000");
        assertThat(engine.evaluate(roomy, new BigDecimal("50000.00"), at).reasons()).isEmpty();
        assertThat(engine.evaluate(roomy, new BigDecimal("50000.01"), at).reasons()).containsExactly("HIGH_AMOUNT");
    }

    @Test
    void overLimitIsIndependentOfHighAmount() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        assertThat(engine.evaluate(card, new BigDecimal("40000.00"), at).reasons()).isEmpty();
        assertThat(engine.evaluate(card, new BigDecimal("40000.01"), at).reasons()).containsExactly("OVER_LIMIT", "OVER_BALANCE");
    }

    @Test
    void velocityTriggersAtThresholdAndNotBelow() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(4L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at).reasons()).isEmpty();
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(5L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at).reasons()).containsExactly("VELOCITY");
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(6L);
        assertThat(engine.evaluate(card, new BigDecimal("10"), at).reasons()).containsExactly("VELOCITY");
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

    @Test
    void differentPlaceInsideTheHourIsFlaggedAndTheSamePlaceIsClean() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        when(txnRepo.findLocationsInWindow(eq(1L), eq(at.minus(60, ChronoUnit.MINUTES)), eq(at)))
                .thenReturn(List.of("Mumbai"));
        assertThat(engine.evaluate(card, new BigDecimal("10"), at, "New York").reasons()).containsExactly("GEO_VELOCITY");
        assertThat(engine.evaluate(card, new BigDecimal("10"), at, "mumbai").reasons()).isEmpty();
        assertThat(engine.evaluate(card, new BigDecimal("10"), at, "  ").reasons()).isEmpty();
    }

    @Test
    void aPriorFlagTightensTheNextAmountFor72Hours() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        when(txnRepo.countFlaggedInWindow(eq(1L), eq(at.minus(72, ChronoUnit.HOURS)), eq(at))).thenReturn(1L);
        assertThat(engine.evaluate(card, new BigDecimal("10000.00"), at).reasons()).containsExactly("STEP_UP");
        assertThat(engine.evaluate(card, new BigDecimal("10000.01"), at).reasons()).containsExactly("REPEAT_RISK", "STEP_UP");
    }

    @Test
    void flaggedOnceHighRiskCategoryIsRiskCohortAndSuggestsStepUpAboveThreshold() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        when(txnRepo.countFlaggedInWindow(eq(1L), eq(at.minus(72, ChronoUnit.HOURS)), eq(at))).thenReturn(1L);
        FraudAssessment casino = engine.evaluate(card, new BigDecimal("6000"), at, null, Category.ENTERTAINMENT);
        assertThat(casino.reasons()).containsExactly("RISK_COHORT", "STEP_UP");
        assertThat(casino.stepUpSuggested()).isTrue();
        assertThat(casino.riskScore()).isEqualTo(45);
        assertThat(engine.evaluate(card, new BigDecimal("100"), at, null, Category.DINING).reasons()).isEmpty();
    }

    @Test
    void runningTotalPastTheLimitWarnsEvenWhenEachPurchaseFits() {
        when(txnRepo.countByCardIdAndOccurredAtBetween(eq(1L), eq(at.minus(60, ChronoUnit.SECONDS)), eq(at))).thenReturn(0L);
        when(txnRepo.sumAmountByCardId(1L)).thenReturn(new BigDecimal("40000.00"));
        assertThat(engine.evaluate(card, new BigDecimal("10.00"), at).reasons()).containsExactly("OVER_BALANCE");
    }

    private Card cardWithLimit(String limit) {
        Card roomy = new Card(new AppUser("a@b.com", "h", Role.USER), "1234", "Main", new BigDecimal(limit));
        ReflectionTestUtils.setField(roomy, "id", 1L);
        return roomy;
    }
}
