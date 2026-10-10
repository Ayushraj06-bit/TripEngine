package com.rideflow.location.internal;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * The Lua scripts, loaded once and executed by SHA.
 *
 * <p>{@link DefaultRedisScript} computes the SHA at startup and Spring's script executor tries
 * {@code EVALSHA} first, falling back to {@code EVAL} when Redis answers {@code NOSCRIPT}. That
 * fallback is not a nicety: Redis loses its script cache on restart and on {@code SCRIPT FLUSH},
 * and without it every location update would fail until the application was redeployed.
 */
@Configuration
class LocationScripts {

  @Bean
  RedisScript<List> locationUpdateScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/location_update.lua")));
    script.setResultType(List.class);
    return script;
  }

  @Bean
  RedisScript<Long> routeAppendScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/route_append.lua")));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> tripContextScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/trip_context.lua")));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<List> countInBoxesScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/count_in_boxes.lua")));
    script.setResultType(List.class);
    return script;
  }

  @Bean
  RedisScript<List> staleDriversScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/stale_drivers.lua")));
    script.setResultType(List.class);
    return script;
  }
}
