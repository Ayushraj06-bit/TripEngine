package com.rideflow.location.internal.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Closes a WebSocket session once the access token it connected with expires.
 *
 * <p>The token is checked once, on CONNECT; a session could otherwise outlive it by hours, and a
 * user whose access was withdrawn would keep receiving their trip updates. Closing forces the
 * client to reconnect with a fresh token - and to resync with {@code GET /trips/active}, which it
 * does after every connect anyway.
 *
 * <p>Per instance, no ShedLock: each instance can only close its own sessions.
 */
@Component
class ExpiringSessions implements WebSocketHandlerDecoratorFactory {

  /** Session attribute holding the {@code exp} of the token the session connected with. */
  static final String TOKEN_EXPIRES_AT = "rideflow.tokenExpiresAt";

  static final CloseStatus TOKEN_EXPIRED =
      CloseStatus.POLICY_VIOLATION.withReason("Access token expired");

  private static final Logger log = LoggerFactory.getLogger(ExpiringSessions.class);

  private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
  private final Clock clock;

  ExpiringSessions(Clock clock) {
    this.clock = clock;
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        open.put(session.getId(), session);
        super.afterConnectionEstablished(session);
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
          throws Exception {
        open.remove(session.getId());
        super.afterConnectionClosed(session, status);
      }
    };
  }

  @Scheduled(fixedDelayString = "${rideflow.location.session-expiry-check-interval}")
  void closeExpired() {
    closeExpired(clock.instant());
  }

  /** Closes every session whose token expired at or before {@code now}. */
  void closeExpired(Instant now) {
    for (WebSocketSession session : open.values()) {
      Object expiresAt = session.getAttributes().get(TOKEN_EXPIRES_AT);
      if (expiresAt instanceof Instant expiry && !now.isBefore(expiry)) {
        try {
          session.close(TOKEN_EXPIRED);
        } catch (IOException | RuntimeException e) {
          // Closed under us, or mid-send: either way it is going. The others still are.
          log.debug("Could not close expired session {}", session.getId(), e);
        }
        open.remove(session.getId());
      }
    }
  }
}
