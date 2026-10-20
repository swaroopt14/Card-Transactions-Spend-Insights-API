package com.swaroop.spendinsights.service;

import java.math.BigDecimal;

/**
 * Facts the synchronous clearing decision already computed.
 * Published inside the purchase transaction and handled only after that transaction commits.
 */
public record PurchaseCleared(
        Long transactionId,
        Long cardId,
        BigDecimal amount,
        Long timeDeltaSeconds,
        boolean placeChanged,
        int highRiskCategory,
        String decision,
        String reason,
        int riskScore,
        String outcome) {}
