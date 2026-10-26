package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 1,000 drivers reporting concurrently.
 *
 * <p>A correctness test under contention, not a throughput benchmark: measured numbers belong to
 * the k6 runs in Phase 10 and {@code docs/performance.md}. Time is simulated on a {@link
 * MutableClock} in rounds of 1.5 seconds - seven rounds, 10.5 seconds - so the outcome of every
 * update is determined, and the assertions can be exact rather than statistical.
 *
 * <p>The sharp part is that each driver sends <em>two</em> updates in every round, concurrently and
 * at the same instant. Exactly one may be accepted: whichever the script sees second is either out
 * of order or inside the throttle interval. If the sequence and throttle checks were a Java read
 * followed by a separate Redis write, both halves of a pair could pass their check before either
 * wrote, and this test would count two acceptances.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LocationConcurrencyIntegrationTest.Clocks.class})
@Tag("integration")
class LocationConcurrencyIntegrationTest {

  private static final int DRIVERS = 1_000;
  private static final int ROUNDS = 7;
  private static final Duration ROUND = Duration.ofMillis(1_500);

  /** About 22 m per round: 53 km/h, well inside the speed limit and above min-move-meters. */
  private static final double STEP_DEGREES = 0.0002;

  /** The second update of a pair sits about 5 m further on. */
  private static final double PAIR_OFFSET_DEGREES = 0.00005;

  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired private LocationIngestService ingest;
  @Autowired private DriverStateStore store;
  @Autowired private StringRedisTemplate redis;

  private record Sent(long seq, double lat, double lng, String outcome) {}

  @Test
  void acceptsExactlyOneOfEachConcurrentPairAndEndsOnTheLastAcceptedPosition() throws Exception {
    CLOCK.set(START);
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();

    List<UUID> drivers = new ArrayList<>(DRIVERS);
    for (int i = 0; i < DRIVERS; i++) {
      UUID driverId = UUID.randomUUID();
      drivers.add(driverId);
      // Half available, so both the GEO write and the "remove from every set" branch
      // run under contention.
      if (i % 2 == 0) {
        store.applyStatusChange(driverId, DriverStatus.AVAILABLE.name(), VehicleType.CAR, 1);
      }
    }

    Map<UUID, Queue<Sent>> sent = new ConcurrentHashMap<>();
    AtomicInteger failures = new AtomicInteger();

    try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
      for (int round = 1; round <= ROUNDS; round++) {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(DRIVERS * 2);

        for (int i = 0; i < DRIVERS; i++) {
          UUID driverId = drivers.get(i);
          GeoPoint base = startPosition(i);
          double lat = base.latitude() + round * STEP_DEGREES;

          // The two halves of the pair: seq 2r-1 and 2r, released together.
          for (int half = 0; half < 2; half++) {
            long seq = 2L * round - 1 + half;
            double pairLat = lat + half * PAIR_OFFSET_DEGREES;
            pool.execute(
                () -> {
                  try {
                    startGate.await();
                    String outcome =
                        ingest.accept(
                            driverId,
                            new LocationUpdate(
                                pairLat, base.longitude(), 10, null, null, seq, CLOCK.instant()));
                    sent.computeIfAbsent(driverId, ignored -> new ConcurrentLinkedQueue<>())
                        .add(new Sent(seq, pairLat, base.longitude(), outcome));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                  } catch (RuntimeException e) {
                    failures.incrementAndGet();
                  } finally {
                    finished.countDown();
                  }
                });
          }
        }

        startGate.countDown();
        assertThat(finished.await(120, TimeUnit.SECONDS)).as("round %d finished", round).isTrue();
        CLOCK.advance(ROUND);
      }
    }

    assertThat(failures).as("updates that threw").hasValue(0);

    List<Sent> all = sent.values().stream().flatMap(Queue::stream).toList();
    Map<String, Long> outcomes =
        all.stream().collect(Collectors.groupingBy(Sent::outcome, Collectors.counting()));

    // One acceptance per driver per round, and every loser lost for a legitimate
    // reason. A spoof here would mean the speed check misread a plausible path.
    assertThat(outcomes.get("accepted")).isEqualTo((long) DRIVERS * ROUNDS);
    assertThat(
            outcomes.getOrDefault("throttled", 0L)
                + outcomes.getOrDefault("ignored_out_of_order", 0L))
        .isEqualTo((long) DRIVERS * ROUNDS);
    assertThat(outcomes).containsOnlyKeys("accepted", "throttled", "ignored_out_of_order");

    for (UUID driverId : drivers) {
      Queue<Sent> mine = sent.get(driverId);
      Sent lastAccepted =
          mine.stream()
              .filter(s -> "accepted".equals(s.outcome()))
              .max((a, b) -> Long.compare(a.seq(), b.seq()))
              .orElseThrow();

      // The stored state is exactly the last update that was accepted - never a
      // rejected one, never an older one that happened to be written last.
      Object storedSeq = redis.opsForHash().get("driver:state:" + driverId, "seq");
      assertThat(Long.parseLong(String.valueOf(storedSeq)))
          .as("stored seq for %s", driverId)
          .isEqualTo(lastAccepted.seq());
      assertThat(store.lastKnownPosition(driverId))
          .get()
          .extracting(GeoPoint::latitude)
          .isEqualTo(lastAccepted.lat());
    }

    assertThat(redis.opsForZSet().size("drivers:available:CAR")).isEqualTo(DRIVERS / 2L);
    assertThat(redis.opsForZSet().size("drivers:lastseen")).isEqualTo((long) DRIVERS);
  }

  /** Spread across a 10 km square of the service area, 100 m apart. */
  private static GeoPoint startPosition(int index) {
    return new GeoPoint(12.90 + (index % 100) * 0.001, 77.55 + (index / 100) * 0.001);
  }
}
