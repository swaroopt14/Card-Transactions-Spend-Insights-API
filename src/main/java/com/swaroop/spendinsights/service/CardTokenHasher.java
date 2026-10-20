package com.swaroop.spendinsights.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint for a card reference. This service never receives a PAN or CVV.
 * The hash is built from the owner email, the persisted card id, the last four digits, and the
 * stable {@code accountReferenceId}. The same inputs always produce the same token.
 */
public final class CardTokenHasher {
    private CardTokenHasher() {}

    public static String hash(String pepper, String ownerEmail, Long cardId, String last4, String accountReferenceId) {
        String payload = String.join("|",
                nullToEmpty(pepper),
                nullToEmpty(ownerEmail).toLowerCase(),
                cardId == null ? "" : cardId.toString(),
                nullToEmpty(last4),
                nullToEmpty(accountReferenceId));
        return sha256Hex(payload);
    }

    static String sha256Hex(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
