package com.swaroop.spendinsights.repository;

import com.swaroop.spendinsights.domain.Category;
import java.math.BigDecimal;

/** Projection for the monthly spend aggregation query. */
public record CategorySpend(Category category, BigDecimal total, Long count) {}
