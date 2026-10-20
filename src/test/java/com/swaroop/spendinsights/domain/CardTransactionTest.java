package com.swaroop.spendinsights.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class CardTransactionTest {
    @Test
    void flagSetsFlaggedAndReason() {
        Card card = new Card(new AppUser("a@b.com", "x", Role.USER), "1234", "Main", new BigDecimal("10000"));
        CardTransaction t = new CardTransaction(card, new BigDecimal("10"), "Cafe", Category.DINING, Instant.now());
        assertThat(t.isFlagged()).isFalse();
        t.flag("HIGH_AMOUNT");
        assertThat(t.isFlagged()).isTrue();
        assertThat(t.getFlagReason()).isEqualTo("HIGH_AMOUNT");
    }
}
