package com.rideflow.trip.internal;

import com.rideflow.pricing.TripFees;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.Money;
import com.rideflow.shared.MutableClock;
import com.rideflow.trip.DriverPositions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * What every trip integration test shares, so they share one application context - and one set of
 * containers - rather than starting their own.
 */
@TestConfiguration(proxyBeanMethods = false)
class TripTestConfiguration {

  static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  static final MutableClock CLOCK = new MutableClock(START);
  static final PlacedPositions POSITIONS = new PlacedPositions();

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

  /**
   * Positions a test places by hand, instead of drivers streaming them over WebSocket. The real,
   * Redis-backed implementation belongs to {@code location} and is tested there.
   */
  static final class PlacedPositions implements DriverPositions {

    private final Map<UUID, GeoPoint> positions = new ConcurrentHashMap<>();

    void place(UUID driverId, GeoPoint position) {
      positions.put(driverId, position);
    }

    void clear() {
      positions.clear();
    }

    @Override
    public Optional<GeoPoint> currentPosition(UUID driverId) {
      return Optional.ofNullable(positions.get(driverId));
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
  }
}
