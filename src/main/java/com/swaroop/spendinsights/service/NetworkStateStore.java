package com.swaroop.spendinsights.service;

/**
 * Sliding window of purchase attempts and the current dynamic-CVV hash for one card token.
 * The hash is what gets stored. The code itself is not.
 */
public interface NetworkStateStore {
    long incrementVelocity(String cardToken, long windowSeconds);

    void storeDynamicCvv(String cardToken, String codeHash, long ttlSeconds);

    boolean matchesDynamicCvv(String cardToken, String codeHash);
}
