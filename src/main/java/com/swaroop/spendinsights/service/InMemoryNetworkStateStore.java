package com.swaroop.spendinsights.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local stand-in used when NETWORK_STATE is memory, including tests. */
@Component
@ConditionalOnProperty(name = "app.network.state", havingValue = "memory", matchIfMissing = true)
public class InMemoryNetworkStateStore implements NetworkStateStore {
    private final ConcurrentHashMap<String, Deque<Long>> velocity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cvv> cvv = new ConcurrentHashMap<>();

    @Override
    public long incrementVelocity(String cardToken, long windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        Deque<Long> hits = velocity.computeIfAbsent(cardToken, key -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.removeFirst();
            hits.addLast(now);
            return hits.size();
        }
    }

    @Override
    public void storeDynamicCvv(String cardToken, String codeHash, long ttlSeconds) {
        cvv.put(cardToken, new Cvv(codeHash, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    @Override
    public boolean matchesDynamicCvv(String cardToken, String codeHash) {
        Cvv current = cvv.get(cardToken);
        return current != null && current.expiresAtMs >= System.currentTimeMillis() && current.hash.equals(codeHash);
    }

    private record Cvv(String hash, long expiresAtMs) {}
}
