package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Test
    void monthWindowIncludesStartAndExcludesNextMonthStart() {
        AppUser me = users.save(new AppUser("bounds@x.com", "h", Role.USER));
        Card mine = cards.save(new Card(me, "3333", "Mine", new BigDecimal("100000")));
        txns.save(new CardTransaction(mine, new BigDecimal("10.00"), "Start", Category.DINING, Instant.parse("2026-10-01T00:00:00Z")));
        txns.save(new CardTransaction(mine, new BigDecimal("999.00"), "Next", Category.TRAVEL, Instant.parse("2026-11-01T00:00:00Z")));

        List<CategorySpend> rows = txns.monthlySpendByCategory("bounds@x.com",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).category()).isEqualTo(Category.DINING);
        assertThat(rows.get(0).total()).isEqualByComparingTo("10.00");
        assertThat(rows.get(0).count()).isEqualTo(1L);
    }

    @Test
    void monthlySpendIncludesFlaggedTransactions() {
        AppUser me = users.save(new AppUser("flagged-sum@x.com", "h", Role.USER));
        Card mine = cards.save(new Card(me, "4444", "Mine", new BigDecimal("100000")));
        Instant oct = Instant.parse("2026-10-10T10:00:00Z");
        CardTransaction flagged = new CardTransaction(mine, new BigDecimal("20.00"), "Flagged", Category.DINING, oct);
        flagged.flag("HIGH_AMOUNT");
        txns.save(flagged);

        List<CategorySpend> rows = txns.monthlySpendByCategory("flagged-sum@x.com",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.total()).isEqualByComparingTo("20.00");
            assertThat(row.count()).isEqualTo(1L);
        });
    }

    @Test
    void velocityCountIsInclusiveAndExcludesTheInstantOutsideTheWindow() {
        AppUser me = users.save(new AppUser("velocity@x.com", "h", Role.USER));
        Card mine = cards.save(new Card(me, "5555", "Mine", new BigDecimal("100000")));
        Instant at = Instant.parse("2026-08-01T12:00:00Z");
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "Edge", Category.OTHER, at.minus(60, ChronoUnit.SECONDS)));
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "Mid", Category.OTHER, at.minusSeconds(30)));
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "Now", Category.OTHER, at));
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "Before", Category.OTHER, at.minusSeconds(61)));
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "After", Category.OTHER, at.plusSeconds(1)));

        long count = txns.countByCardIdAndOccurredAtBetween(mine.getId(), at.minus(60, ChronoUnit.SECONDS), at);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void flaggedQuerySkipsCleanTransactionsAndSortsNewestFirst() {
        AppUser me = users.save(new AppUser("flags@x.com", "h", Role.USER));
        Card mine = cards.save(new Card(me, "6666", "Mine", new BigDecimal("100000")));
        txns.save(new CardTransaction(mine, new BigDecimal("1.00"), "Clean", Category.DINING, Instant.parse("2026-08-01T12:00:00Z")));
        CardTransaction older = new CardTransaction(mine, new BigDecimal("2.00"), "Old", Category.DINING, Instant.parse("2026-08-01T11:00:00Z"));
        older.flag("HIGH_AMOUNT");
        CardTransaction newer = new CardTransaction(mine, new BigDecimal("3.00"), "New", Category.DINING, Instant.parse("2026-08-01T13:00:00Z"));
        newer.flag("VELOCITY");
        txns.save(older);
        txns.save(newer);

        assertThat(txns.findByFlaggedTrueOrderByOccurredAtDesc())
                .extracting(CardTransaction::getMerchant)
                .containsExactly("New", "Old");
    }
}
