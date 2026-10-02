package com.rideflow.matching.internal;

import com.rideflow.notification.Notifications;
import com.rideflow.shared.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * What the matching integration tests share, so they share one context and one set of containers.
 */
@TestConfiguration(proxyBeanMethods = false)
class MatchingTestConfiguration {

  static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  static final MutableClock CLOCK = new MutableClock(START);
  static final RecordingNotifications NOTIFICATIONS = new RecordingNotifications();

  @Bean
  @Primary
  Clock testClock() {
    return CLOCK;
  }

  @Bean
  @Primary
  Notifications testNotifications() {
    return NOTIFICATIONS;
  }

  /**
   * Pushes recorded instead of sent: a test sees who was offered what, and plays the driver's app.
   * The real, Redis-fanned-out implementation is tested over a real WebSocket elsewhere.
   */
  static final class RecordingNotifications implements Notifications {

    record Sent(UUID userId, String destination, Object payload) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendToUser(UUID userId, String destination, Object payload) {
      sent.add(new Sent(userId, destination, payload));
    }

    /** Every offer pushed so far, oldest first. */
    List<Sent> offers() {
      return sent.stream().filter(s -> s.payload() instanceof OfferView).toList();
    }

    List<OfferView> offersTo(UUID driverId) {
      return offers().stream()
          .filter(s -> s.userId().equals(driverId))
          .map(s -> (OfferView) s.payload())
          .toList();
    }

    List<Sent> withdrawalsTo(UUID driverId) {
      return sent.stream()
          .filter(s -> s.userId().equals(driverId))
          .filter(s -> s.payload() instanceof Matcher.OfferWithdrawn)
          .toList();
    }

    void clear() {
      sent.clear();
    }
  }
}
