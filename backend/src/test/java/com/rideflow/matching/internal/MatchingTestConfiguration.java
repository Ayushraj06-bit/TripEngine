package com.rideflow.matching.internal;

import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.shared.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
  RealtimePublisher testNotifications() {
    return NOTIFICATIONS;
  }

  /**
   * Pushes recorded instead of sent: a test sees who was offered what, and plays the driver's app.
   * The real, Redis-fanned-out implementation is tested over a real WebSocket elsewhere.
   */
  static final class RecordingNotifications implements RealtimePublisher {

    record Sent(UUID userId, String destination, RealtimeMessage message) {

      Object payload() {
        return message.payload();
      }
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public void toUser(UUID userId, String destination, RealtimeMessage message) {
      sent.add(new Sent(userId, destination, message));
    }

    /** The admin live map's broadcasts: not what these tests watch. */
    @Override
    public void toTopic(String destination, RealtimeMessage message) {}

    /** Offers carry no seq; trip updates, which share this context, do. */
    @Override
    public long nextSeq(UUID tripId) {
      return seq.incrementAndGet();
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
