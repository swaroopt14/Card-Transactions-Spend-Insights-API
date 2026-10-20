package com.swaroop.spendinsights.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.UUID;

/** Redis-backed velocity window and dynamic-CVV hash. Active only when NETWORK_STATE=redis. */
public class RedisNetworkStateStore implements NetworkStateStore {
    private final StringRedisTemplate redis;

    public RedisNetworkStateStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public long incrementVelocity(String cardToken, long windowSeconds) {
        String key = "vel:" + cardToken;
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(key, 0, now - windowSeconds * 1000d);
        redis.opsForZSet().add(key, UUID.randomUUID().toString(), now);
        redis.expire(key, Duration.ofSeconds(windowSeconds));
        Long count = redis.opsForZSet().zCard(key);
        return count == null ? 0 : count;
    }

    @Override
    public void storeDynamicCvv(String cardToken, String codeHash, long ttlSeconds) {
        redis.opsForValue().set("dcvv:" + cardToken, codeHash, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean matchesDynamicCvv(String cardToken, String codeHash) {
        String stored = redis.opsForValue().get("dcvv:" + cardToken);
        return stored != null && stored.equals(codeHash);
    }
}
