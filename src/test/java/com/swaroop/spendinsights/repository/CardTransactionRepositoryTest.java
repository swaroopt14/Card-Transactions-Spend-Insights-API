package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CardTransactionRepositoryTest {
    @Autowired AppUserRepository users;
    @Autowired CardRepository cards;
    @Autowired CardTransactionRepository txns;

    @Test
    void aggregatesMonthlySpendByCategoryForOwnerOnly() {
        AppUser me = users.save(new AppUser("me@x.com", "h", Role.USER));
        AppUser other = users.save(new AppUser("other@x.com", "h", Role.USER));
        Card mine = cards.save(new Card(me, "1111", "Mine", new BigDecimal("100000")));
        Card theirs = cards.save(new Card(other, "2222", "Theirs", new BigDecimal("100000")));
        Instant oct = Instant.parse("2026-10-10T10:00:00Z");
        txns.save(new CardTransaction(mine, new BigDecimal("100.00"), "A", Category.DINING, oct));
        txns.save(new CardTransaction(mine, new BigDecimal("50.00"), "B", Category.DINING, oct));
        txns.save(new CardTransaction(mine, new BigDecimal("500.00"), "C", Category.TRAVEL, oct));
        txns.save(new CardTransaction(mine, new BigDecimal("999.00"), "Nov", Category.TRAVEL, Instant.parse("2026-11-02T00:00:00Z")));
        txns.save(new CardTransaction(theirs, new BigDecimal("777.00"), "X", Category.DINING, oct));

        List<CategorySpend> rows = txns.monthlySpendByCategory("me@x.com",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).category()).isEqualTo(Category.TRAVEL);
        assertThat(rows.get(0).total()).isEqualByComparingTo("500.00");
        assertThat(rows.get(1).total()).isEqualByComparingTo("150.00");
        assertThat(rows.get(1).count()).isEqualTo(2L);
    }
}
