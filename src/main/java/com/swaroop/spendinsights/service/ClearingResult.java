package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.web.dto.Dtos.StepUpChallengeResponse;

/** Either a written purchase or a step-up hold. The latency is the synchronous pipeline time. */
public record ClearingResult(PostedTransaction posted, StepUpChallengeResponse stepUp, long latencyMs) {
    public static final String BUDGET_HEADER = "X-Clearing-Budget-Ms";
    public static final String LATENCY_HEADER = "X-Clearing-Latency-Ms";
}
