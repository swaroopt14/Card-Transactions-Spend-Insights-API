package com.swaroop.spendinsights.service;

/** Two requests stored the same key at the same moment. The caller should return the winner. */
public class IdempotencyRace extends RuntimeException {
    public IdempotencyRace() { super("Idempotency key was stored by a concurrent request"); }
}
