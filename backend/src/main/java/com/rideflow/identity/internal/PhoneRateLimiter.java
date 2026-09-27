package com.rideflow.identity.internal;

import com.rideflow.shared.BusinessException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-phone login throttle.
 *
 * <p>Separate from {@code RateLimitFilter} because this limit keys on the phone number, which lives
 * in the request body. A filter would have to read and buffer the body to see it, leaving the
 * stream consumed for the controller; calling this from the service once the phone is normalised is
 * both simpler and more accurate. Normalisation is what makes it work at all - without it the same
 * person under three spellings gets three buckets, and the limit means nothing.
 *
 * <p><strong>The capacity is deliberately above the lockout threshold.</strong> Account lockout is
 * the precise control: ten wrong passwords locks that account for fifteen minutes. This limiter is
 * the flood guard behind it. Set below the lockout threshold - it was five, against a threshold of
 * ten - the guard pre-empts the control, {@code failed_login_count} never reaches ten, and the
 * lockout becomes unreachable code that looks implemented.
 *
 * <p>Time comes from the injected {@link Clock} via Bucket4j's {@link TimeMeter}, not from {@code
 * System.nanoTime}. PROJECT_SPEC section 12 forbids sleeping in tests, and a bucket on the system
 * clock cannot be refilled by advancing a test clock - it would also leak state between tests
 * sharing one context.
 *
 * <p>Buckets are per instance and in memory. PROJECT_SPEC section 3 asks for Bucket4j on Redis,
 * which is the right answer for a cluster, since two instances currently double every limit. That
 * is a swap of the bucket factory here and no change anywhere else.
 */
@Component
class PhoneRateLimiter {

  /** Comfortably above the ten-attempt lockout, so lockout is what a guesser actually hits. */
  private static final int CAPACITY = 15;

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final TimeMeter timeMeter;

  PhoneRateLimiter(Clock clock) {
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
   * Consumes one attempt for {@code normalisedPhone}.
   *
   * @throws BusinessException 429, carrying the seconds to wait, when the limit is exhausted
   */
  void checkLoginAttempt(String normalisedPhone) {
    ConsumptionProbe probe =
        buckets
            .computeIfAbsent(normalisedPhone, ignored -> newBucket())
            .tryConsumeAndReturnRemaining(1);

    if (!probe.isConsumed()) {
      // Round up: telling a client to retry in 0 seconds invites an immediate retry.
      long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
      throw new BusinessException(
          "RATE_LIMITED",
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many attempts for this number. Try again in %d seconds.".formatted(seconds),
          Map.of("retryAfterSeconds", seconds));
    }
  }

  /**
   * Discards every bucket.
   *
   * <p>For tests, which share one application context and reset the clock backwards between cases.
   * A wall-clock bucket does not refill when time moves backwards, so without this a drained bucket
   * from an earlier test silently fails a later one.
   */
  void reset() {
    buckets.clear();
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(CAPACITY).refillGreedy(CAPACITY, WINDOW).build())
        .withCustomTimePrecision(timeMeter)
        .build();
  }
}
