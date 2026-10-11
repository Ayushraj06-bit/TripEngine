package com.rideflow.pricing.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** Loaded once and run by SHA, falling back to EVAL when Redis has lost its script cache. */
@Configuration
class PricingScripts {

  @Bean
  RedisScript<List> quoteConsumeScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/quote_consume.lua")));
    script.setResultType(List.class);
    return script;
  }

  /** Monotonic: the surge cache's notion of elapsed time (see {@link RedisSurgeProvider}). */
  @Bean
  Ticker surgeCacheTicker() {
    return Ticker.systemTicker();
  }

  @Bean
  RedisScript<Long> surgeDemandScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/surge_demand.lua")));
    script.setResultType(Long.class);
    return script;
  }
}
