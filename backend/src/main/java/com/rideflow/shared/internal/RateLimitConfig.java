package com.rideflow.shared.internal;

import com.rideflow.shared.RateLimitBuckets;
import io.lettuce.core.RedisClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  /**
   * On the Redis client Spring Data already configured - the same server, and the same 1 s command
   * timeout - asked for only when the first bucket is needed.
   */
  @Bean(destroyMethod = "close")
  RateLimitBuckets rateLimitBuckets(LettuceConnectionFactory redis, Clock clock) {
    return new RateLimitBuckets(
        () -> (RedisClient) redis.getNativeClient(),
        clock,
        redis.getClientConfiguration().getCommandTimeout());
  }
}
