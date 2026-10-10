package com.rideflow.pricing.internal;

import com.rideflow.pricing.SurgeDemand;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Ride requests per cell and minute: {@code surge:demand:{cell}:{epochMinute}}. */
@Component
class SurgeDemandStore implements SurgeDemand {

  private final StringRedisTemplate redis;
  private final RedisScript<Long> surgeDemandScript;
  private final int precision;
  private final Duration ttl;

  SurgeDemandStore(
      StringRedisTemplate redis,
      RedisScript<Long> surgeDemandScript,
      RideFlowProperties properties) {
    this.redis = redis;
    this.surgeDemandScript = surgeDemandScript;
    this.precision = properties.surge().geohashPrecision();
    this.ttl = Duration.ofMinutes(properties.surge().demandTtlMinutes());
  }

  @Override
  public void recordRequest(UUID requestId, GeoPoint pickup, Instant requestedAt) {
    String cell = Geohash.encode(pickup, precision);
    long minute = SurgeKeys.epochMinute(requestedAt);
    redis.execute(
        surgeDemandScript,
        List.of(
            SurgeKeys.counted(requestId), SurgeKeys.demand(cell, minute), SurgeKeys.ACTIVE_CELLS),
        Long.toString(ttl.toMillis()),
        Long.toString(minute),
        cell);
  }
}
