package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.Card;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple, explainable rule-based fraud flags (not ML).
 * Rules: HIGH_AMOUNT, OVER_LIMIT, VELOCITY (too many txns in one minute).
 */
@Component
public class FraudRuleEngine {
    private final CardTransactionRepository txnRepo;
    private final BigDecimal highAmountThreshold;
    private final int maxTxnPerMinute;

    public FraudRuleEngine(CardTransactionRepository txnRepo,
                           @Value("${app.fraud.high-amount-threshold}") BigDecimal highAmountThreshold,
                           @Value("${app.fraud.max-txn-per-minute}") int maxTxnPerMinute) {
        this.txnRepo = txnRepo;
        this.highAmountThreshold = highAmountThreshold;
        this.maxTxnPerMinute = maxTxnPerMinute;
    }

    /** Returns the list of triggered rule names; empty list means "clean". */
    public List<String> evaluate(Card card, BigDecimal amount, Instant at) {
        List<String> reasons = new ArrayList<>();
        if (amount.compareTo(highAmountThreshold) > 0) reasons.add("HIGH_AMOUNT");
        if (amount.compareTo(card.getCreditLimit()) > 0) reasons.add("OVER_LIMIT");
        if (card.getId() != null) {
            long recent = txnRepo.countByCardIdAndOccurredAtBetween(card.getId(), at.minus(1, ChronoUnit.MINUTES), at);
            if (recent >= maxTxnPerMinute) reasons.add("VELOCITY");
        }
        return reasons;
    }
}
