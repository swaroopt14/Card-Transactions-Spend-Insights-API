package com.swaroop.spendinsights.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkGuardTest {
    @Test
    void blocksWhenTheWindowCountPassesTheLimit() {
        NetworkGuard guard = new NetworkGuard(new InMemoryNetworkStateStore(), null, 2, 60, 60, "pepper");
        guard.observe("token");
        guard.observe("token");
        assertThatThrownBy(() -> guard.observe("token"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Velocity block");
    }
}
