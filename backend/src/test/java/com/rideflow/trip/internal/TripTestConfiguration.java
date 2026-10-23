package com.rideflow.trip.internal;

import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.pricing.FareBreakdown;
import com.rideflow.pricing.TripFees;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.Money;
import com.rideflow.shared.MutableClock;
import com.rideflow.trip.DriverPositions;
import com.rideflow.trip.RoutePoint;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripRoutes;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * What every trip integration test shares, so they share one application context - and one set of
 * containers - rather than starting their own.
 */
@TestConfiguration(proxyBeanMethods = false)
class TripTestConfiguration {

  static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  static final MutableClock CLOCK = new MutableClock(START);
  static final PlacedPositions POSITIONS = new PlacedPositions();
  static final RecordedPushes PUSHES = new RecordedPushes();

  @Bean
  @Primary
  RealtimePublisher testRealtime() {
    return PUSHES;
  }

  /** Pushes recorded, so a test sees who was told what; delivery itself is tested elsewhere. */
  static final class RecordedPushes implements RealtimePublisher {

    record Push(UUID userId, String destination, RealtimeMessage message) {}

    private final List<Push> pushes = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public void toUser(UUID userId, String destination, RealtimeMessage message) {
      pushes.add(new Push(userId, destination, message));
    }

    /** The admin live map's broadcasts: not what these tests watch. */
    @Override
    public void toTopic(String destination, RealtimeMessage message) {}

    @Override
    public long nextSeq(UUID tripId) {
      return seq.incrementAndGet();
    }

    List<Push> to(UUID userId, String destination) {
      return pushes.stream()
          .filter(push -> push.userId().equals(userId) && push.destination().equals(destination))
          .toList();
    }

    List<Push> all() {
      return List.copyOf(pushes);
    }

    void clear() {
      pushes.clear();
    }
  }

  /** Where each booking's module listener ran, by trip (BookingTraceIntegrationTest). */
  static final Map<UUID, ListenerRun> LISTENER_RUNS = new ConcurrentHashMap<>();

  /** The trace a listener ran in, and whether on a virtual thread. */
  record ListenerRun(String traceId, boolean virtualThread) {}

  @Bean
  ListenerTraces listenerTraces(Tracer tracer) {
    return new ListenerTraces(tracer);
  }

  /**
   * A module listener like matching's - asynchronous, after the booking commits - that notes the
   * trace it runs in. Phase 10 found these running on a fallback executor with no trace at all. Not
   * final: the listener is asynchronous and transactional, so Spring subclasses it.
   */
  static class ListenerTraces {

    private final Tracer tracer;

    ListenerTraces(Tracer tracer) {
      this.tracer = tracer;
    }

    @ApplicationModuleListener
    void on(TripRequested event) {
      TraceContext context = tracer.currentTraceContext().context();
      LISTENER_RUNS.put(
          event.tripId(),
          new ListenerRun(
              context == null ? "none" : context.traceId(), Thread.currentThread().isVirtual()));
    }
  }

  @Bean
  @Primary
  Clock testClock() {
    return CLOCK;
  }

  @Bean
  @Primary
  DriverPositions testDriverPositions() {
    return POSITIONS;
  }

  @Bean
  @Primary
  PausableFees testTripFees(@Qualifier("rateCardService") TripFees rateCards) {
    return new PausableFees(rateCards);
  }

  @Bean
  @Primary
  BreakableRoutes testTripRoutes(@Qualifier("tripRouteStore") TripRoutes routes) {
    return new BreakableRoutes(routes);
  }

  /**
   * The real, Redis-backed routes, with a switch to make reading fail as it would with Redis down.
   */
  static final class BreakableRoutes implements TripRoutes {

    private final TripRoutes real;
    private volatile boolean broken;

    BreakableRoutes(TripRoutes real) {
      this.real = real;
    }

    void breakReads() {
      broken = true;
    }

    void reset() {
      broken = false;
    }

    @Override
    public List<RoutePoint> route(UUID tripId) {
      if (broken) {
        throw new RedisConnectionFailureException("Redis is down");
      }
      return real.route(tripId);
    }

    @Override
    public void forget(UUID tripId) {
      real.forget(tripId);
    }
  }

  /**
   * Positions a test places by hand, instead of drivers streaming them over WebSocket. The real,
   * Redis-backed implementation belongs to {@code location} and is tested there.
   */
  static final class PlacedPositions implements DriverPositions {

    private final Map<UUID, GeoPoint> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> reportedAt = new ConcurrentHashMap<>();

    /** As though the driver reported {@code position} just now, by the test clock. */
    void place(UUID driverId, GeoPoint position) {
      positions.put(driverId, position);
      reportedAt.put(driverId, CLOCK.instant());
    }

    void clear() {
      positions.clear();
      reportedAt.clear();
    }

    @Override
    public Optional<GeoPoint> currentPosition(UUID driverId) {
      return Optional.ofNullable(positions.get(driverId));
    }

    @Override
    public Optional<Instant> lastReportedAt(UUID driverId) {
      return Optional.ofNullable(reportedAt.get(driverId));
    }
  }

  /**
   * The real fees, with a way to hold one lookup mid-transaction. Cancelling looks the fee up after
   * reading the trip, so pausing here is how a test makes an optimistic-lock conflict happen on
   * demand rather than by luck.
   */
  static final class PausableFees implements TripFees {

    private final TripFees real;
    private volatile CountDownLatch waiting;
    private volatile CountDownLatch resume;

    PausableFees(TripFees real) {
      this.real = real;
    }

    /**
     * Holds the next lookup until {@link #resume()}.
     *
     * @return counted down once the lookup is waiting
     */
    CountDownLatch pauseNextLookup() {
      resume = new CountDownLatch(1);
      waiting = new CountDownLatch(1);
      return waiting;
    }

    void resume() {
      resume.countDown();
    }

    void reset() {
      waiting = null;
      resume = null;
    }

    @Override
    public Money cancellationFee(UUID rateCardId) {
      CountDownLatch paused = waiting;
      if (paused != null) {
        waiting = null;
        CountDownLatch go = resume;
        paused.countDown();
        try {
          if (!go.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The test never resumed the paused fee lookup");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return real.cancellationFee(rateCardId);
    }

    @Override
    public FareBreakdown fareFor(
        UUID rateCardId, long distanceMeters, long durationSeconds, BigDecimal surgeMultiplier) {
      return real.fareFor(rateCardId, distanceMeters, durationSeconds, surgeMultiplier);
    }

    @Override
    public Money waitingPerMinute(UUID rateCardId) {
      return real.waitingPerMinute(rateCardId);
    }
  }
}
