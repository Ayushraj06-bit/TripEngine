package com.rideflow.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Generates UUIDv7 identifiers (RFC 9562).
 *
 * <p>PROJECT_SPEC section 7 requires time-ordered ids. A random UUIDv4 primary key scatters inserts
 * across the whole B-tree, so every insert dirties a different page; a v7 id carries its timestamp
 * in the high bits, so new rows land together at the right-hand edge of the index.
 *
 * <p>Layout:
 *
 * <pre>
 *   bits   0-47  unix timestamp, milliseconds
 *   bits  48-51  version (0111)
 *   bits  52-63  counter, monotonic within a millisecond
 *   bits  64-65  variant (10)
 *   bits  66-127 random
 * </pre>
 *
 * <p>The 12-bit counter is what makes the ordering useful in practice. At 2,000 simulated drivers
 * many ids are created in the same millisecond, and filling those bits randomly would leave the
 * order within a millisecond arbitrary. The counter keeps ids strictly increasing, so a sort by id
 * is a sort by creation order.
 *
 * <p>Time comes only from the injected {@link Clock}, per PROJECT_SPEC section 11.
 *
 * <p>This class is thread-safe and lock-free.
 */
@Component
public class IdGenerator {

  private static final int COUNTER_BITS = 12;
  private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;
  private static final int VERSION = 7;
  private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
  private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

  private final Clock clock;
  private final RandomGenerator random;

  /** Packs the last timestamp and counter into one value so both advance atomically. */
  private final AtomicLong state = new AtomicLong();

  public IdGenerator(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = new SecureRandom();
  }

  /** A new, time-ordered identifier. */
  public UUID newId() {
    long nowMillis = clock.millis();

    long stamp =
        state.updateAndGet(
            previous -> {
              long previousMillis = previous >>> COUNTER_BITS;
              if (nowMillis > previousMillis) {
                return nowMillis << COUNTER_BITS;
              }
              // Same millisecond, or a clock that moved backwards: take the next counter
              // value. If the counter overflows, the carry lands in the timestamp, which
              // keeps ids strictly increasing at the cost of borrowing from the next
              // millisecond. Both are better than emitting a duplicate or going backwards.
              return previous + 1;
            });

    long timestamp = stamp >>> COUNTER_BITS;
    long counter = stamp & COUNTER_MASK;

    long mostSignificantBits = (timestamp << 16) | ((long) VERSION << 12) | counter;
    long leastSignificantBits = (random.nextLong() & RANDOM_B_MASK) | VARIANT_BITS;

    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  /**
   * The creation time encoded in a UUIDv7.
   *
   * <p>Useful for debugging and for range queries over time.
   *
   * @throws IllegalArgumentException if {@code uuid} is not version 7
   */
  public static Instant timestampOf(UUID uuid) {
    Objects.requireNonNull(uuid, "uuid");
    if (uuid.version() != VERSION) {
      throw new IllegalArgumentException(
          "expected a UUIDv7 but got version " + uuid.version() + ": " + uuid);
    }
    return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
  }
}
