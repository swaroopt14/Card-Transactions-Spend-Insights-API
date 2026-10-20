package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.service.InsightsService;
import com.swaroop.spendinsights.web.dto.Dtos.MonthlySpendResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {
    private final InsightsService insights;
    public InsightsController(InsightsService insights) { this.insights = insights; }

    /** Example: GET /api/v1/insights/monthly?month=2026-10 */
    @GetMapping("/monthly")
    public MonthlySpendResponse monthly(Authentication auth, @RequestParam String month) {
        return insights.monthly(auth.getName(), YearMonth.parse(month));
    }
}
