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
 * Explainable fraud flags. A flag does not decline the transaction.
 * <p>
 * Boundaries, all compared with {@link BigDecimal#compareTo} or a count:
 * <ul>
 *   <li>{@code HIGH_AMOUNT} when amount is strictly greater than the configured threshold.
 *       An amount equal to the threshold is clean.</li>
 *   <li>{@code OVER_LIMIT} when amount is strictly greater than the card credit limit.
 *       An amount equal to the limit is clean.</li>
 *   <li>{@code VELOCITY} when the number of already-saved transactions on this card in the
 *       inclusive window {@code [at - window, at]} is greater than or equal to the configured
 *       count. The transaction being evaluated is not saved yet, so it is not part of the count.
 *       With the default of 5, the 6th transaction in the window is the first one flagged.</li>
 * </ul>
 * The count-then-insert check is safe across app instances only because
 * {@code TransactionService} locks the card row in the same database transaction.
 * Two requests that arrive together queue on that lock, so the second one sees the first insert.
 */
@Component
public class FraudRuleEngine {
    private final CardTransactionRepository txnRepo;
    private final BigDecimal highAmountThreshold;
    private final int maxTxnPerWindow;
    private final long velocityWindowSeconds;

    public FraudRuleEngine(CardTransactionRepository txnRepo,
                           @Value("${app.fraud.high-amount-threshold}") BigDecimal highAmountThreshold,
                           @Value("${app.fraud.max-txn-per-minute}") int maxTxnPerWindow,
                           @Value("${app.fraud.velocity-window-seconds:60}") long velocityWindowSeconds) {
        if (highAmountThreshold.signum() <= 0) {
            throw new IllegalArgumentException("high-amount-threshold must be positive");
        }
        if (maxTxnPerWindow < 1) throw new IllegalArgumentException("max-txn-per-minute must be >= 1");
        if (velocityWindowSeconds < 1) throw new IllegalArgumentException("velocity-window-seconds must be >= 1");
        this.txnRepo = txnRepo;
        this.highAmountThreshold = highAmountThreshold;
        this.maxTxnPerWindow = maxTxnPerWindow;
        this.velocityWindowSeconds = velocityWindowSeconds;
    }

    /** Returns triggered rule ids in a stable order. An empty list means the transaction is clean. */
    public List<String> evaluate(Card card, BigDecimal amount, Instant at) {
        List<String> reasons = new ArrayList<>();
        if (amount.compareTo(highAmountThreshold) > 0) reasons.add("HIGH_AMOUNT");
        if (amount.compareTo(card.getCreditLimit()) > 0) reasons.add("OVER_LIMIT");
        if (card.getId() != null) {
            long recent = txnRepo.countByCardIdAndOccurredAtBetween(
                    card.getId(), at.minus(velocityWindowSeconds, ChronoUnit.SECONDS), at);
            if (recent >= maxTxnPerWindow) reasons.add("VELOCITY");
        }
        return reasons;
    }
}
