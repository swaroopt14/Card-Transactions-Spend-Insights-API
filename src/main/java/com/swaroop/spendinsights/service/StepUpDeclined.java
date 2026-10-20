package com.swaroop.spendinsights.service;

import java.math.BigDecimal;

/** A step-up challenge that failed. Published after that failure commits. No ledger row exists. */
public record StepUpDeclined(Long challengeId, Long cardId, BigDecimal amount, int riskScore, String reason) {}
