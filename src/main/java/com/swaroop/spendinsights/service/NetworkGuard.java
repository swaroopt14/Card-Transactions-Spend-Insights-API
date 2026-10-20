package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.Card;
import com.swaroop.spendinsights.web.dto.Dtos.DynamicCvvResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;

/**
 * Second stage. Counts attempts for the card token and checks a dynamic CVV when one was issued
 * and the client sent it. A block refuses the purchase before the rule engine runs.
 */
@Component
public class NetworkGuard {
    private final NetworkStateStore store;
    private final CardService cards;
    private final int velocityBlock;
    private final long velocityWindowSeconds;
    private final long dynamicCvvSeconds;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();

    public NetworkGuard(NetworkStateStore store, CardService cards,
                        @Value("${app.network.velocity-block:30}") int velocityBlock,
                        @Value("${app.network.velocity-window-seconds:60}") long velocityWindowSeconds,
                        @Value("${app.network.dynamic-cvv-seconds:60}") long dynamicCvvSeconds,
                        @Value("${app.fraud.token-pepper}") String pepper) {
        if (velocityBlock < 1) throw new IllegalArgumentException("velocity-block must be >= 1");
        this.store = store;
        this.cards = cards;
        this.velocityBlock = velocityBlock;
        this.velocityWindowSeconds = velocityWindowSeconds;
        this.dynamicCvvSeconds = dynamicCvvSeconds;
        this.pepper = pepper == null ? "" : pepper;
    }

    public void observe(String cardToken) {
        long count = store.incrementVelocity(cardToken, velocityWindowSeconds);
        if (count > velocityBlock) throw new BusinessRuleException("Velocity block");
    }

    public void checkDynamicCvv(String cardToken, String presented) {
        if (presented == null || presented.isBlank()) return;
        if (!store.matchesDynamicCvv(cardToken, hash(presented))) {
            throw new BusinessRuleException("Dynamic CVV does not match");
        }
    }

    public DynamicCvvResponse issue(String email, Long cardId) {
        Card card = cards.getOwned(email, cardId);
        String code = String.format("%03d", random.nextInt(1000));
        store.storeDynamicCvv(card.getCardToken(), hash(code), dynamicCvvSeconds);
        return new DynamicCvvResponse(code, (int) dynamicCvvSeconds);
    }

    private String hash(String code) {
        return CardTokenHasher.sha256Hex(pepper + "|dcvv|" + code);
    }
}
