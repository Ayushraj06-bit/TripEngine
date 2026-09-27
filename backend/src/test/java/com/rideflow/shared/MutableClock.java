package com.rideflow.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand.
 *
 * <p>This is what makes PROJECT_SPEC section 12's ban on {@code Thread.sleep} possible. Offer
 * timeouts, the cancellation grace period, the no-show timer and refresh-token expiry are all
 * durations measured against the injected {@link Clock}; a test advances this instead of waiting.
 *
 * <p>Always UTC, matching the production clock, so a test cannot accidentally depend on a zone.
 *
 * <p><strong>One caveat.</strong> Advancing this does not expire a Redis key: Redis TTLs run on
 * Redis's own wall clock and nothing in the JVM can move them. Anything whose correctness depends
 * on expiry must therefore be driven by a value derived from this clock - the {@code offers:expiry}
 * ZSET score, for instance - and treat the Redis TTL purely as garbage collection.
 */
public final class MutableClock extends Clock {

  private volatile Instant instant;

  public MutableClock(Instant instant) {
    this.instant = instant;
  }

  /** A clock fixed at a fixed, arbitrary instant, for tests that do not care which. */
  public static MutableClock fixedAtEpochDay() {
    return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  }

  public void set(Instant newInstant) {
    this.instant = newInstant;
  }

  public void advance(Duration by) {
    this.instant = this.instant.plus(by);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("MutableClock is always UTC");
  }

  @Override
  public Instant instant() {
    return instant;
  }
}
