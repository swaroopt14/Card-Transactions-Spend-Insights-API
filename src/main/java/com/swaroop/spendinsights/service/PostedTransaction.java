package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.web.dto.Dtos.TransactionResponse;

/** A purchase that was just written, or the original purchase returned for a repeated key. */
public record PostedTransaction(TransactionResponse body, boolean replayed) {}
