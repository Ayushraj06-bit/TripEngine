package com.rideflow.notification.internal;

import com.rideflow.notification.Notifications;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery across instances through Redis Pub/Sub on {@value #CHANNEL}.
 *
 * <p>A user's WebSocket lives on one instance, and the instance with something to say is usually
 * another. So every message is published to Redis, every instance receives it, and each hands it to
 * its local STOMP broker - which delivers it if that user is connected here and drops it if not. No
 * instance has to know where anyone is connected, and it works for any number of them with no extra
 * infrastructure.
 */
@Component
class RedisNotifications implements Notifications, MessageListener {

  static final String CHANNEL = "rt:events";

  /** What travels through Redis. The payload is already JSON, so it is converted only once. */
  record Envelope(UUID userId, String destination, String payload) {}

  private final StringRedisTemplate redis;
  private final SimpMessagingTemplate messaging;
  private final JsonMapper json;

  RedisNotifications(StringRedisTemplate redis, SimpMessagingTemplate messaging, JsonMapper json) {
    this.redis = redis;
    this.messaging = messaging;
    this.json = json;
  }

  @Override
  public void sendToUser(UUID userId, String destination, Object payload) {
    redis.convertAndSend(
        CHANNEL,
        json.writeValueAsString(
            new Envelope(userId, destination, json.writeValueAsString(payload))));
  }

  /** A message published by any instance, this one included. */
  @Override
  public void onMessage(Message message, byte[] pattern) {
    Envelope envelope = json.readValue(message.getBody(), Envelope.class);
    messaging.convertAndSendToUser(
        envelope.userId().toString(),
        envelope.destination(),
        envelope.payload().getBytes(StandardCharsets.UTF_8),
        Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
  }
}
