package com.rideflow.shared;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/**
 * A token bucket per key - a phone number, a rider, a client address.
 *
 * <p>One implementation for every limit in the system, because all of them need the same two things
 * that are easy to get wrong separately:
 *
 * <ul>
 *   <li><strong>Time from the injected {@link Clock}.</strong> Bucket4j defaults to {@code
 *       System.nanoTime}, which a test clock cannot move. Buckets then never refill in a test, and
 *       drained buckets leak from one test into the next.
 *   <li><strong>A bound on memory.</strong> One bucket per key, never removed, grows with every
 *       phone number or address that ever made a request.
 * </ul>
 *
 * <p>In memory, per instance. PROJECT_SPEC section 3 wants Bucket4j on Redis, which is the right
 * answer for more than one instance - two instances currently double every limit. This class is the
 * one place that changes when that happens.
 */
public final class KeyedRateLimiter {

  /** The property carrying the wait, which the exception handler turns into {@code Retry-After}. */
  public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";

  /**
   * Past this many keys, idle buckets are dropped. A full bucket is indistinguishable from a new
   * one, so dropping it loses nothing.
   */
  private static final int EVICT_IDLE_ABOVE = 100_000;

  private final int capacity;
  private final Duration window;
  private final String subject;
  private final TimeMeter timeMeter;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * @param capacity requests allowed per window
   * @param subject what is being limited, for the error message: "login attempts for this number"
   */
  public KeyedRateLimiter(int capacity, Duration window, String subject, Clock clock) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive, was " + capacity);
    }
    this.capacity = capacity;
    this.window = window;
    this.subject = subject;
    this.timeMeter =
        new TimeMeter() {
          @Override
          public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
          }

          @Override
          public boolean isWallClockBased() {
            return true;
          }
        };
  }

  /**
   * Takes one token for {@code key}.
   *
   * @return seconds to wait, or empty when the request may proceed
   */
  public OptionalLong tryConsume(String key) {
    if (buckets.size() > EVICT_IDLE_ABOVE) {
      // ponytail: linear sweep once the map is large; fine at this size, a TTL map if
      // the key space ever grows well past it.
      buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= capacity);
    }
    ConsumptionProbe probe =
        buckets.computeIfAbsent(key, ignored -> newBucket()).tryConsumeAndReturnRemaining(1);
    return probe.isConsumed()
        ? OptionalLong.empty()
        : OptionalLong.of(secondsToWait(probe.getNanosToWaitForRefill()));
  }

  /**
   * Takes one token for {@code key}, or refuses.
   *
   * @throws BusinessException 429 {@code RATE_LIMITED}, carrying {@value #RETRY_AFTER_SECONDS}
   */
  public void consume(String key) {
    OptionalLong wait = tryConsume(key);
    if (wait.isPresent()) {
      long seconds = wait.getAsLong();
      throw new BusinessException(
          "RATE_LIMITED",
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many %s. Try again in %d seconds.".formatted(subject, seconds),
          Map.of(RETRY_AFTER_SECONDS, seconds));
    }
  }

  /**
   * Discards every bucket.
   *
   * <p>For tests, which share one context and move the clock backwards between cases. A bucket does
   * not refill when time runs backwards, so a drained one would silently fail a later test.
   */
  public void reset() {
    buckets.clear();
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
        .withCustomTimePrecision(timeMeter)
        .build();
  }

  private static long secondsToWait(long nanos) {
    // Round up: telling a client to retry in 0 seconds invites an immediate retry.
    return Math.max(1, Math.ceilDiv(nanos, Duration.ofSeconds(1).toNanos()));
  }
}
