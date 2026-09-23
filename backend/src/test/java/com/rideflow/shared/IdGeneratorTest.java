package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

  private static final Instant FIXED_INSTANT = Instant.ofEpochMilli(1_767_225_600_000L);

  /**
   * A clock frozen at one millisecond. Every id in a test therefore lands in the same millisecond,
   * which is exactly the case the monotonic counter exists for.
   */
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

  @Test
  void producesVersion7Uuids() {
    UUID id = new IdGenerator(FIXED_CLOCK).newId();

    assertThat(id.version()).isEqualTo(7);
    // Variant 2 is the RFC 9562 layout; anything else would not be a valid UUID.
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void encodesTheClockTimeInTheId() {
    UUID id = new IdGenerator(FIXED_CLOCK).newId();

    assertThat(IdGenerator.timestampOf(id)).isEqualTo(FIXED_INSTANT);
  }

  @Test
  void takesTimeOnlyFromTheInjectedClock() {
    Instant past = Instant.parse("2020-01-01T00:00:00Z");
    UUID id = new IdGenerator(Clock.fixed(past, ZoneOffset.UTC)).newId();

    assertThat(IdGenerator.timestampOf(id)).isEqualTo(past);
  }

  @Test
  void ordersIdsCreatedWithinTheSameMillisecond() {
    IdGenerator generator = new IdGenerator(FIXED_CLOCK);

    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      ids.add(generator.newId());
    }

    // The timestamp and counter both live in the most significant bits, so comparing
    // those alone is comparing creation order. The low bits are random and carry no
    // ordering.
    for (int i = 1; i < ids.size(); i++) {
      assertThat(ids.get(i).getMostSignificantBits())
          .withFailMessage("id %d was not greater than its predecessor", i)
          .isGreaterThan(ids.get(i - 1).getMostSignificantBits());
    }
  }

  @Test
  void neverRepeatsAnIdWhenTheClockDoesNotMove() {
    IdGenerator generator = new IdGenerator(FIXED_CLOCK);

    Set<UUID> ids = new java.util.HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(generator.newId());
    }

    // 10,000 exceeds the 12-bit counter, so this also covers the rollover path.
    assertThat(ids).hasSize(10_000);
  }

  @Test
  void keepsIdsIncreasingWhenTheClockGoesBackwards() {
    MutableClock clock = new MutableClock(FIXED_INSTANT);
    IdGenerator generator = new IdGenerator(clock);

    UUID before = generator.newId();
    clock.set(FIXED_INSTANT.minusSeconds(60));
    UUID after = generator.newId();

    // An NTP correction must not let a later row sort before an earlier one.
    assertThat(after.getMostSignificantBits()).isGreaterThan(before.getMostSignificantBits());
  }

  @Test
  void generatesUniqueIdsUnderConcurrency() throws Exception {
    int threads = 8;
    int idsPerThread = 500;
    IdGenerator generator = new IdGenerator(FIXED_CLOCK);

    Set<UUID> ids = new ConcurrentSkipListSet<>();
    AtomicInteger failures = new AtomicInteger();
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(threads);

    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        executor.execute(
            () -> {
              try {
                startGate.await();
                for (int i = 0; i < idsPerThread; i++) {
                  ids.add(generator.newId());
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.incrementAndGet();
              } finally {
                finished.countDown();
              }
            });
      }

      // Release every thread at once, so they genuinely contend for the counter.
      startGate.countDown();
      assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(failures).hasValue(0);
    assertThat(ids).hasSize(threads * idsPerThread);
  }

  @Test
  void refusesToReadATimestampFromANonVersion7Uuid() {
    UUID version4 = UUID.randomUUID();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> IdGenerator.timestampOf(version4))
        .withMessageContaining("version");
  }

  /** A clock whose instant the test moves explicitly. */
  private static final class MutableClock extends Clock {

    private volatile Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant newInstant) {
      this.instant = newInstant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      throw new UnsupportedOperationException("test clock is always UTC");
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
