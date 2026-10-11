package com.rideflow.payment.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import com.rideflow.pricing.AvailableDriverCounts;
import com.rideflow.shared.Money;
import com.rideflow.shared.MutableClock;
import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.TripPayments;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * What the payment and rating integration tests share, so they share one context: a clock they can
 * move, and a gateway they can take down.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentTestConfiguration {

  public static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  public static final MutableClock CLOCK = new MutableClock(START);

  @Bean
  @Primary
  Clock testClock() {
    return CLOCK;
  }

  @Bean
  @Primary
  SwitchableGateway testGateway(@Qualifier("mockPaymentGateway") PaymentGateway gateway) {
    return new SwitchableGateway(gateway);
  }

  @Bean
  @Primary
  BreakableTripPayments testTripPayments(@Qualifier("tripPaymentRecorder") TripPayments real) {
    return new BreakableTripPayments(real);
  }

  /** The trip's payment status, with a switch to make recording it fail: a database blip. */
  public static final class BreakableTripPayments implements TripPayments {

    private final TripPayments real;
    private final java.util.concurrent.atomic.AtomicInteger refused =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean broken;

    BreakableTripPayments(TripPayments real) {
      this.real = real;
    }

    public void breakRecording() {
      broken = true;
    }

    public void reset() {
      broken = false;
      refused.set(0);
    }

    /** How many times recording was refused since the last reset. */
    public int refused() {
      return refused.get();
    }

    @Override
    public void recordPaymentStatus(UUID tripId, PaymentStatus status) {
      if (broken) {
        refused.incrementAndGet();
        throw new IllegalStateException("The database blinked");
      }
      real.recordPaymentStatus(tripId, status);
    }

    @Override
    public void settleOutstanding(UUID riderId) {
      real.settleOutstanding(riderId);
    }
  }

  /** The surge cache ages by this clock, so a test can step past its five seconds. */
  @Bean
  @Primary
  Ticker testSurgeCacheTicker() {
    return () -> TimeUnit.MILLISECONDS.toNanos(CLOCK.millis());
  }

  @Bean
  @Primary
  PausableDriverCounts testDriverCounts(
      @Qualifier("availableDriverCounter") AvailableDriverCounts counts) {
    return new PausableDriverCounts(counts);
  }

  /** The real supply count, with a way to hold one call: a surge tick caught mid-run. */
  public static final class PausableDriverCounts implements AvailableDriverCounts {

    private final AvailableDriverCounts real;
    private volatile CountDownLatch waiting;
    private volatile CountDownLatch resume;

    PausableDriverCounts(AvailableDriverCounts real) {
      this.real = real;
    }

    /**
     * Holds the next count until {@link #resume()}.
     *
     * @return counted down once the count is waiting
     */
    public CountDownLatch pauseNext() {
      resume = new CountDownLatch(1);
      waiting = new CountDownLatch(1);
      return waiting;
    }

    public void resume() {
      if (resume != null) {
        resume.countDown();
      }
    }

    @Override
    public List<Long> countIn(List<Box> boxes) {
      CountDownLatch pause = waiting;
      if (pause != null) {
        waiting = null;
        pause.countDown();
        try {
          if (!resume.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Never resumed");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return real.countIn(boxes);
    }
  }

  /** The real mock gateway, with a switch for an outage. */
  static final class SwitchableGateway implements PaymentGateway {

    private final PaymentGateway real;
    private volatile boolean down;

    SwitchableGateway(PaymentGateway real) {
      this.real = real;
    }

    void goDown() {
      down = true;
    }

    void reset() {
      down = false;
    }

    @Override
    public Session createPayment(UUID topupId, Money amount) {
      if (down) {
        throw new com.rideflow.shared.BusinessException(
            "PAYMENT_GATEWAY_UNAVAILABLE",
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "The payment gateway is not answering. Try again shortly.");
      }
      return real.createPayment(topupId, amount);
    }
  }
}
