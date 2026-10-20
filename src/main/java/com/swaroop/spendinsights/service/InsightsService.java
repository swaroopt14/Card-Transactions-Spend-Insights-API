package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.repository.CardTransactionRepository;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class InsightsService {
    private final CardTransactionRepository txns;

    public InsightsService(CardTransactionRepository txns) { this.txns = txns; }

    /**
     * Half-open UTC month {@code [first instant of month, first instant of next month)}.
     * Flagged transactions are included. Totals come from the database aggregation, not from
     * loading every row into memory.
     */
    @Transactional(readOnly = true)
    public MonthlySpendResponse monthly(String email, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<CategoryTotal> rows = txns.monthlySpendByCategory(email, from, to).stream()
                .map(r -> new CategoryTotal(r.category(), r.total(), r.count()))
                .toList();
        BigDecimal grand = rows.stream().map(CategoryTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MonthlySpendResponse(month.toString(), grand, rows);
    }
}
