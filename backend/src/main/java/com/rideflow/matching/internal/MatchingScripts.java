package com.rideflow.matching.internal;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** Loaded once and run by SHA, falling back to EVAL when Redis has lost its script cache. */
@Configuration
class MatchingScripts {

  @Bean
  RedisScript<Long> matchStartScript() {
    return script("redis/match_start.lua", Long.class);
  }

  @Bean
  RedisScript<String> offerCreateScript() {
    return script("redis/offer_create.lua", String.class);
  }

  @Bean
  RedisScript<List> offerAcceptScript() {
    return script("redis/offer_accept.lua", List.class);
  }

  @Bean
  RedisScript<List> offerCloseScript() {
    return script("redis/offer_close.lua", List.class);
  }

  @Bean
  RedisScript<String> matchFinishScript() {
    return script("redis/match_finish.lua", String.class);
  }

  @Bean
  RedisScript<Long> unlockScript() {
    return script("redis/unlock.lua", Long.class);
  }

  private static <T> RedisScript<T> script(String path, Class<T> resultType) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
    script.setResultType(resultType);
    return script;
  }
}
