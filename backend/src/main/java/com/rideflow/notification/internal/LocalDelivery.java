package com.rideflow.notification.internal;

import com.rideflow.notification.internal.RedisRealtimePublisher.Delivery;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * This instance's half of the fan-out: every published message arrives here, and is delivered only
 * if its user has a session on this instance.
 *
 * <p>Who is connected here comes from Spring's {@link SimpUserRegistry}, which is exactly the
 * per-instance tracker the design needs: it follows {@code SessionConnectedEvent} and {@code
 * SessionDisconnectEvent}, and keeps every session of a user - phone and laptop both receive. A
 * hand-written copy would only duplicate it.
 *
 * <p>Not a bean of its own: {@link NotificationConfig} creates it, and a test creates two, one per
 * pretend instance.
 */
class LocalDelivery implements MessageListener {

  private final SimpUserRegistry users;
  private final SimpMessagingTemplate messaging;
  private final JsonMapper json;

  LocalDelivery(SimpUserRegistry users, SimpMessagingTemplate messaging, JsonMapper json) {
    this.users = users;
    this.messaging = messaging;
    this.json = json;
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    Delivery delivery = json.readValue(message.getBody(), Delivery.class);
    String user = delivery.userId().toString();
    if (users.getUser(user) == null) {
      // Connected to another instance, or not at all: dropped, without an error.
      return;
    }
    messaging.convertAndSendToUser(
        user,
        delivery.destination(),
        delivery.envelope().getBytes(StandardCharsets.UTF_8),
        Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
  }
}
