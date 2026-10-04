package com.rideflow.notification.internal;

import com.rideflow.shared.RideFlowProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The {@code seq} of a trip's real-time messages: {@code trip:seq:{tripId}} in Redis (PROJECT_SPEC
 * section 7).
 *
 * <p>Redis {@code INCR} rather than a counter in memory, because a trip's messages come from every
 * instance - the rider's cancel lands on one, the driver's next position on another - and a client
 * can only drop stragglers if the numbers rise across all of them.
 */
@Component
class TripSequence {

  private final StringRedisTemplate redis;
  private final RedisScript<Long> tripSequenceScript;
  private final Duration ttl;

  TripSequence(
      StringRedisTemplate redis,
      RedisScript<Long> tripSequenceScript,
      RideFlowProperties properties) {
    this.redis = redis;
    this.tripSequenceScript = tripSequenceScript;
    this.ttl = Duration.ofHours(properties.notification().sequenceTtlHours());
  }

  static String key(UUID tripId) {
    return "trip:seq:" + tripId;
  }

  /** The next number for this trip: 1, 2, 3, never repeated, whichever instance asks. */
  long next(UUID tripId) {
    return redis.execute(tripSequenceScript, List.of(key(tripId)), Long.toString(ttl.toMillis()));
  }
}
