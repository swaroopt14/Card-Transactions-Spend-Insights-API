package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.Card;
import com.swaroop.spendinsights.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Third stage. The score is the linear rule model in {@link FraudRuleEngine}:
 * amount, limit, balance, card velocity, a place change, and a high-risk category.
 * A score at or above the authorize threshold holds the purchase for step-up.
 */
@Component
public class InferenceEngine {
    private final FraudRuleEngine rules;
    private final int authorizeThreshold;

    public InferenceEngine(FraudRuleEngine rules,
                           @Value("${app.network.authorize-threshold:70}") int authorizeThreshold) {
        if (authorizeThreshold < 1 || authorizeThreshold > 100) {
            throw new IllegalArgumentException("authorize-threshold must be from 1 to 100");
        }
        this.rules = rules;
        this.authorizeThreshold = authorizeThreshold;
    }

    public FraudAssessment evaluate(Card card, BigDecimal amount, Instant at, String location, Category category) {
        return rules.evaluate(card, amount, at, location, category);
    }

    public boolean hold(FraudAssessment assessment) {
        return assessment != null && assessment.riskScore() >= authorizeThreshold;
    }

    public boolean highRisk(Category category) { return rules.highRisk(category); }
}
