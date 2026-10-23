package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.location.DriverWentStale;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;

/**
 * The stale-driver sweep, with time moved rather than waited for.
 *
 * <p>Delivery is asserted through a real {@link ApplicationModuleListener}, not by recording what
 * was published. That distinction found a bug: the sweep first published {@code DriverWentStale}
 * with no transaction active, and Spring skips transactional event listeners in that case - so the
 * event was demonstrably published and reached nobody. A recorder of publications would have
 * passed.
 */
@SpringBootTest(properties = "rideflow.location.stale-sweep-batch-size=10")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaleDriverSweepIntegrationTest.Probe.class})
@Tag("integration")
class StaleDriverSweepIntegrationTest {

  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);
  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);

  @TestConfiguration
  static class Probe {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    StaleEventRecorder staleEventRecorder() {
      return new StaleEventRecorder();
    }
  }

  /**
   * Receives the event the way the trip module will: as a module listener, after commit.
   *
   * <p>Read only through methods. The listener annotation makes this bean a proxy, and a field read
   * through a proxy reaches the proxy's own empty copy rather than the target's list.
   */
  static class StaleEventRecorder {
    private final List<DriverWentStale> received = new CopyOnWriteArrayList<>();

    @ApplicationModuleListener
    void on(DriverWentStale event) {
      received.add(event);
    }

    List<DriverWentStale> received() {
      return List.copyOf(received);
    }

    void clear() {
      received.clear();
    }
  }

  @Autowired private StaleDriverSweeper sweeper;
  @Autowired private LocationIngestService ingest;
  @Autowired private DriverStateStore store;
  @Autowired private StringRedisTemplate redis;
  @Autowired private StaleEventRecorder recorder;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    recorder.clear();
  }

  /** An available car that has reported one position, now. */
  private UUID availableDriverPingingNow() {
    UUID driverId = UUID.randomUUID();
    store.applyStatusChange(driverId, DriverStatus.AVAILABLE.name(), VehicleType.CAR, null);
    ingest.accept(
        driverId,
        new LocationUpdate(
            MG_ROAD.latitude(), MG_ROAD.longitude(), 10, null, null, 1, CLOCK.instant()));
    return driverId;
  }

  private boolean isTracked(UUID driverId) {
    return redis.opsForZSet().score("drivers:lastseen", driverId.toString()) != null;
  }

  private boolean isOfferable(UUID driverId) {
    return redis.opsForZSet().score("drivers:available:CAR", driverId.toString()) != null;
  }

  @Test
  void removesAQuietDriverAndTellsTheirListeners() {
    UUID quiet = availableDriverPingingNow();

    // stale-after-seconds is 30.
    CLOCK.advance(Duration.ofSeconds(31));
    sweeper.sweep();

    assertThat(isTracked(quiet)).isFalse();
    // A stale driver must never be offered a trip.
    assertThat(isOfferable(quiet)).isFalse();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(recorder.received())
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.driverId()).isEqualTo(quiet);
                          assertThat(event.lastSeenAt()).isEqualTo(START);
                          assertThat(event.detectedAt()).isEqualTo(START.plusSeconds(31));
                        }));
  }

  @Test
  void leavesADriverWhoPingedRecentlyAlone() {
    UUID quiet = availableDriverPingingNow();
    CLOCK.advance(Duration.ofSeconds(25));
    UUID active = availableDriverPingingNow();

    CLOCK.advance(Duration.ofSeconds(10));
    sweeper.sweep();

    assertThat(isTracked(quiet)).isFalse();
    assertThat(isTracked(active)).isTrue();
    assertThat(isOfferable(active)).isTrue();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(recorder.received())
                    .extracting(DriverWentStale::driverId)
                    .containsExactly(quiet));
  }

  @Test
  void doesNotSweepADriverAtExactlyTheThreshold() {
    UUID driverId = availableDriverPingingNow();

    CLOCK.advance(Duration.ofSeconds(29));
    sweeper.sweep();

    assertThat(isTracked(driverId)).isTrue();
  }

  @Test
  void keepsTheDriversStateSoTheyResumeWithoutTheDriverModule() {
    UUID driverId = availableDriverPingingNow();
    CLOCK.advance(Duration.ofSeconds(31));
    sweeper.sweep();

    // Going stale is not going offline. The status and vehicle type survive, so the
    // next ping puts them straight back on the map.
    assertThat(redis.opsForHash().get("driver:state:" + driverId, "status")).isEqualTo("AVAILABLE");

    ingest.accept(
        driverId,
        new LocationUpdate(
            MG_ROAD.latitude(), MG_ROAD.longitude(), 10, null, null, 2, CLOCK.instant()));
    assertThat(isOfferable(driverId)).isTrue();
  }

  @Test
  void drainsABacklogLargerThanOneBatchInASingleRun() {
    // The batch size is 10 in this test, so 25 stale drivers need three script calls.
    List<UUID> quiet = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      quiet.add(availableDriverPingingNow());
    }

    CLOCK.advance(Duration.ofSeconds(31));
    sweeper.sweep();

    assertThat(quiet).noneMatch(this::isTracked);
    assertThat(redis.opsForZSet().size("drivers:available:CAR")).isZero();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(recorder.received())
                    .extracting(DriverWentStale::driverId)
                    .containsExactlyInAnyOrderElementsOf(quiet));
  }

  @Test
  void publishesNothingWhenNobodyIsStale() {
    availableDriverPingingNow();

    sweeper.sweep();

    assertThat(recorder.received()).isEmpty();
  }
}
