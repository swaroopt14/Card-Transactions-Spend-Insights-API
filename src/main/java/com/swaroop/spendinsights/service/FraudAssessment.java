package com.swaroop.spendinsights.service;

import java.util.List;

/** Explainable fraud result for one purchase. A score under the authorize threshold can still flag and save the row. */
public record FraudAssessment(List<String> reasons, int riskScore, boolean stepUpSuggested) {
    public boolean flagged() { return reasons != null && !reasons.isEmpty(); }
}
