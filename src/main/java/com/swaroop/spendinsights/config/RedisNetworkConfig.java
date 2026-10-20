package com.swaroop.spendinsights.config;

import com.swaroop.spendinsights.service.NetworkStateStore;
import com.swaroop.spendinsights.service.RedisNetworkStateStore;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@ConditionalOnProperty(name = "app.network.state", havingValue = "redis")
public class RedisNetworkConfig {
    @Bean
    LettuceConnectionFactory networkRedis(@Value("${app.network.redis-url:}") String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) {
            throw new IllegalStateException("NETWORK_STATE=redis requires REDIS_URL");
        }
        RedisURI uri = RedisURI.create(redisUrl);
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getPassword() != null && uri.getPassword().length > 0) {
            cfg.setPassword(new String(uri.getPassword()));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    StringRedisTemplate networkRedisTemplate(LettuceConnectionFactory networkRedis) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(networkRedis);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    NetworkStateStore redisNetworkStateStore(StringRedisTemplate networkRedisTemplate) {
        return new RedisNetworkStateStore(networkRedisTemplate);
    }
}
