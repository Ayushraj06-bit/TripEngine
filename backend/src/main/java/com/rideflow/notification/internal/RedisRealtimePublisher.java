package com.rideflow.notification.internal;

import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes every message to Redis Pub/Sub on {@value #CHANNEL}; {@link LocalDelivery} on each
 * instance hands it to the users connected there. See ADR 0011.
 */
@Component
class RedisRealtimePublisher implements RealtimePublisher {

  static final String CHANNEL = "rt:events";

  /** What the client receives. */
  record Envelope(String type, UUID tripId, Long seq, Instant sentAt, Object payload) {}

  /**
   * What travels through Redis: who it is for, where, and the envelope - already JSON, so it is
   * serialised once however many instances receive it.
   */
  record Delivery(UUID userId, String destination, String envelope) {}

  private final StringRedisTemplate redis;
  private final TripSequence sequence;
  private final JsonMapper json;
  private final Clock clock;

  RedisRealtimePublisher(
      StringRedisTemplate redis, TripSequence sequence, JsonMapper json, Clock clock) {
    this.redis = redis;
    this.sequence = sequence;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public void toUser(UUID userId, String destination, RealtimeMessage message) {
    Long seq = message.seq();
    if (seq == null && message.tripId() != null) {
      seq = sequence.next(message.tripId());
    }
    String envelope =
        json.writeValueAsString(
            new Envelope(
                message.type(), message.tripId(), seq, clock.instant(), message.payload()));
    redis.convertAndSend(
        CHANNEL, json.writeValueAsString(new Delivery(userId, destination, envelope)));
  }

  @Override
  public long nextSeq(UUID tripId) {
    return sequence.next(tripId);
  }
}
