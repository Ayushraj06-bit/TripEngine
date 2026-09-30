package com.rideflow.identity.internal;

import com.rideflow.shared.KeyedRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Per-phone login throttle.
 *
 * <p>Separate from {@code RateLimitFilter} because this limit keys on the phone number, which lives
 * in the request body. A filter would have to read and buffer the body to see it; calling this from
 * the service once the phone is normalised is simpler and more accurate. Normalisation is what
 * makes it work at all - without it the same person under three spellings gets three buckets.
 *
 * <p><strong>The capacity is deliberately above the lockout threshold.</strong> Account lockout is
 * the precise control: ten wrong passwords locks that account for fifteen minutes. This limiter is
 * the flood guard behind it. Set below the lockout threshold - it was five, against ten - the guard
 * pre-empts the control, {@code failed_login_count} never reaches ten, and the lockout becomes
 * unreachable code that looks implemented.
 */
@Component
class PhoneRateLimiter {

  /** Comfortably above the ten-attempt lockout, so lockout is what a guesser actually hits. */
  private static final int CAPACITY = 15;

  private final KeyedRateLimiter limiter;

  PhoneRateLimiter(Clock clock) {
    this.limiter =
        new KeyedRateLimiter(
            CAPACITY, Duration.ofMinutes(1), "login attempts for this number", clock);
  }

  /**
   * @throws com.rideflow.shared.BusinessException 429 when the limit is exhausted
   */
  void checkLoginAttempt(String normalisedPhone) {
    limiter.consume(normalisedPhone);
  }

  /** For tests; see {@link KeyedRateLimiter#reset()}. */
  void reset() {
    limiter.reset();
  }
}
