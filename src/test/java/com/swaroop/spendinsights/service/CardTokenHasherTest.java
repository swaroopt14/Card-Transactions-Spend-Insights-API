package com.swaroop.spendinsights.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CardTokenHasherTest {
    @Test
    void sameInputsProduceTheSameTokenAndDifferentOwnersDoNot() {
        String one = CardTokenHasher.hash("pepper", "a@b.com", 1L, "4321", "ref-1");
        String again = CardTokenHasher.hash("pepper", "a@b.com", 1L, "4321", "ref-1");
        String other = CardTokenHasher.hash("pepper", "c@d.com", 1L, "4321", "ref-1");
        assertThat(one).isEqualTo(again).hasSize(64);
        assertThat(one).isNotEqualTo(other);
    }
}
