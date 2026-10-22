package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class LocationMetricsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final LocationMetrics metrics = new LocationMetrics(meters, mock(DriverStateStore.class));

  @Test
  void countsEachSessionOnceHoweverOftenItsDisconnectIsRaised() {
    metrics.onConnected(connected("phone"));
    metrics.onConnected(connected("laptop"));
    metrics.onDisconnected(disconnected("phone"));
    metrics.onDisconnected(disconnected("phone"));
    // A socket whose STOMP CONNECT was refused - an expired token - never connected.
    metrics.onDisconnected(disconnected("refused"));

    assertThat(meters.get("location.websocket.sessions").gauge().value()).isEqualTo(1);
  }

  private static SessionConnectedEvent connected(String sessionId) {
    return new SessionConnectedEvent(LocationMetricsTest.class, message(sessionId));
  }

  private static SessionDisconnectEvent disconnected(String sessionId) {
    return new SessionDisconnectEvent(
        LocationMetricsTest.class, message(sessionId), sessionId, CloseStatus.NORMAL);
  }

  private static Message<byte[]> message(String sessionId) {
    return MessageBuilder.withPayload(new byte[0])
        .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
        .build();
  }
}
