package com.rideflow.shared;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

/**
 * A token bucket per key - a phone number, a rider, a client address - shared by every instance.
 *
 * <p>One implementation for every limit in the system. The buckets live in Redis ({@link
 * RateLimitBuckets}), on the injected clock: two instances behind a load balancer count against one
 * budget, and a test clock refills a bucket when it moves.
 */
public final class KeyedRateLimiter {

  /** The property carrying the wait, which the exception handler turns into {@code Retry-After}. */
  public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";

  private final String name;
  private final String subject;
  private final RateLimitBuckets buckets;
  private final BucketConfiguration configuration;

  /**
   * @param name the limit's name, which keeps its buckets apart from every other limit's
   * @param capacity requests allowed per window
   * @param subject what is being limited, for the error message: "login attempts for this number"
   */
  public KeyedRateLimiter(
      String name, int capacity, Duration window, String subject, RateLimitBuckets buckets) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive, was " + capacity);
    }
    this.name = name;
    this.subject = subject;
    this.buckets = buckets;
    this.configuration =
        BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
            .build();
  }

  /**
   * Takes one token for {@code key}.
   *
   * @return seconds to wait, or empty when the request may proceed
   * @throws DataAccessResourceFailureException when Redis does not answer: a 503, as for every
   *     other request that needs it
   */
  public OptionalLong tryConsume(String key) {
    ConsumptionProbe probe;
    try {
      probe = buckets.bucket(name, key, () -> configuration).tryConsumeAndReturnRemaining(1);
    } catch (DataAccessException unavailable) {
      throw unavailable;
    } catch (RuntimeException unavailable) {
      throw new DataAccessResourceFailureException(
          "Rate limit " + name + " could not be checked in Redis", unavailable);
    }
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
   * Discards every bucket of this limit.
   *
   * <p>For tests, which share one context and move the clock backwards between cases. A bucket does
   * not refill when time runs backwards, so a drained one would silently fail a later test.
   */
  public void reset() {
    buckets.clear(name);
  }

  private static long secondsToWait(long nanos) {
    // Round up: telling a client to retry in 0 seconds invites an immediate retry.
    return Math.max(1, Math.ceilDiv(nanos, Duration.ofSeconds(1).toNanos()));
  }
}
