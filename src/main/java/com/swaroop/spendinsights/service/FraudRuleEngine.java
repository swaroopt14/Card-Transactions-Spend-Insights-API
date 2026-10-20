package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.Card;
import com.swaroop.spendinsights.domain.Category;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explainable fraud flags. A flag does not decline the transaction.
 * <p>
 * Boundaries, all compared with {@link BigDecimal#compareTo} or a count:
 * <ul>
 *   <li>{@code HIGH_AMOUNT} when amount is strictly greater than the configured threshold.</li>
 *   <li>{@code OVER_LIMIT} when amount is strictly greater than the card credit limit.</li>
 *   <li>{@code OVER_BALANCE} when already-saved purchases plus this amount exceed the limit.</li>
 *   <li>{@code VELOCITY} when already-saved purchases in the inclusive window meet the count.</li>
 *   <li>{@code GEO_VELOCITY} when this place differs from another place on the card in the geo window.</li>
 *   <li>{@code REPEAT_RISK} when the card was flagged in the repeat window and amount exceeds the reduced line.</li>
 *   <li>{@code RISK_COHORT} when the card was flagged in the repeat window and the category is high-risk.</li>
 *   <li>{@code STEP_UP} when the card was flagged in the repeat window and amount exceeds the step-up line.
 *       This ledger records the hint. A payment gateway would challenge the holder.</li>
 * </ul>
 * Risk score is the sum of fixed weights for each fired rule, capped at 100.
 */
@Component
public class FraudRuleEngine {
    private final CardTransactionRepository txnRepo;
    private final BigDecimal highAmountThreshold;
    private final int maxTxnPerWindow;
    private final long velocityWindowSeconds;
    private final long geoWindowMinutes;
    private final long repeatFlagHours;
    private final BigDecimal repeatLimitFactor;
    private final BigDecimal stepUpAmount;
    private final Set<Category> highRiskCategories;

    public FraudRuleEngine(CardTransactionRepository txnRepo,
                           @Value("${app.fraud.high-amount-threshold}") BigDecimal highAmountThreshold,
                           @Value("${app.fraud.max-txn-per-minute}") int maxTxnPerWindow,
                           @Value("${app.fraud.velocity-window-seconds:60}") long velocityWindowSeconds,
                           @Value("${app.fraud.geo-window-minutes:60}") long geoWindowMinutes,
                           @Value("${app.fraud.repeat-flag-hours:72}") long repeatFlagHours,
                           @Value("${app.fraud.repeat-limit-factor:0.20}") BigDecimal repeatLimitFactor,
                           @Value("${app.fraud.step-up-amount:5000}") BigDecimal stepUpAmount,
                           @Value("${app.fraud.high-risk-categories:ENTERTAINMENT,OTHER}") String highRiskCategories) {
        if (highAmountThreshold.signum() <= 0) {
            throw new IllegalArgumentException("high-amount-threshold must be positive");
        }
        if (maxTxnPerWindow < 1) throw new IllegalArgumentException("max-txn-per-minute must be >= 1");
        if (velocityWindowSeconds < 1) throw new IllegalArgumentException("velocity-window-seconds must be >= 1");
        if (geoWindowMinutes < 1) throw new IllegalArgumentException("geo-window-minutes must be >= 1");
        if (repeatFlagHours < 1) throw new IllegalArgumentException("repeat-flag-hours must be >= 1");
        if (repeatLimitFactor.signum() <= 0 || repeatLimitFactor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("repeat-limit-factor must be in (0, 1]");
        }
        if (stepUpAmount.signum() <= 0) throw new IllegalArgumentException("step-up-amount must be positive");
        this.txnRepo = txnRepo;
        this.highAmountThreshold = highAmountThreshold;
        this.maxTxnPerWindow = maxTxnPerWindow;
        this.velocityWindowSeconds = velocityWindowSeconds;
        this.geoWindowMinutes = geoWindowMinutes;
        this.repeatFlagHours = repeatFlagHours;
        this.repeatLimitFactor = repeatLimitFactor;
        this.stepUpAmount = stepUpAmount;
        this.highRiskCategories = parseCategories(highRiskCategories);
    }

    public boolean highRisk(Category category) {
        return category != null && highRiskCategories.contains(category);
    }

    public FraudAssessment evaluate(Card card, BigDecimal amount, Instant at) {
        return evaluate(card, amount, at, null, null);
    }

    public FraudAssessment evaluate(Card card, BigDecimal amount, Instant at, String location) {
        return evaluate(card, amount, at, location, null);
    }

    public FraudAssessment evaluate(Card card, BigDecimal amount, Instant at, String location, Category category) {
        List<String> reasons = new ArrayList<>();
        if (amount.compareTo(highAmountThreshold) > 0) reasons.add("HIGH_AMOUNT");
        if (amount.compareTo(card.getCreditLimit()) > 0) reasons.add("OVER_LIMIT");
        long flaggedRecent = 0;
        if (card.getId() != null) {
            BigDecimal posted = txnRepo.sumAmountByCardId(card.getId());
            if (posted == null) posted = BigDecimal.ZERO;
            if (posted.add(amount).compareTo(card.getCreditLimit()) > 0) reasons.add("OVER_BALANCE");
            long recent = txnRepo.countByCardIdAndOccurredAtBetween(
                    card.getId(), at.minus(velocityWindowSeconds, ChronoUnit.SECONDS), at);
            if (recent >= maxTxnPerWindow) reasons.add("VELOCITY");
            String place = place(location);
            if (place != null) {
                List<String> prior = txnRepo.findLocationsInWindow(
                        card.getId(), at.minus(geoWindowMinutes, ChronoUnit.MINUTES), at);
                boolean moved = prior.stream().map(FraudRuleEngine::place)
                        .anyMatch(existing -> existing != null && !existing.equalsIgnoreCase(place));
                if (moved) reasons.add("GEO_VELOCITY");
            }
            flaggedRecent = txnRepo.countFlaggedInWindow(
                    card.getId(), at.minus(repeatFlagHours, ChronoUnit.HOURS), at);
            BigDecimal reduced = highAmountThreshold.multiply(repeatLimitFactor);
            if (flaggedRecent >= 1 && amount.compareTo(reduced) > 0) reasons.add("REPEAT_RISK");
            if (flaggedRecent >= 1 && category != null && highRiskCategories.contains(category)) {
                reasons.add("RISK_COHORT");
            }
            if (flaggedRecent >= 1 && amount.compareTo(stepUpAmount) > 0) reasons.add("STEP_UP");
        }
        int score = score(reasons);
        boolean stepUp = reasons.contains("STEP_UP");
        return new FraudAssessment(List.copyOf(reasons), score, stepUp);
    }

    static int score(List<String> reasons) {
        int total = 0;
        for (String reason : reasons) {
            total += switch (reason) {
                case "HIGH_AMOUNT" -> 25;
                case "OVER_LIMIT" -> 20;
                case "OVER_BALANCE" -> 20;
                case "VELOCITY" -> 15;
                case "GEO_VELOCITY" -> 25;
                case "REPEAT_RISK" -> 20;
                case "RISK_COHORT" -> 30;
                case "STEP_UP" -> 15;
                default -> 10;
            };
        }
        return Math.min(100, total);
    }

    static String place(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Set<Category> parseCategories(String raw) {
        EnumSet<Category> set = EnumSet.noneOf(Category.class);
        if (raw == null || raw.isBlank()) return set;
        for (String part : raw.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            set.add(Category.valueOf(name));
        }
        return set;
    }
}
