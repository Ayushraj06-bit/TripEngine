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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
 * The ingestion pipeline against a real Redis.
 *
 * <p>Positions are validated partly in Java and partly inside {@code location_update.lua}, so a
 * mock Redis would test half the logic. Time comes from a {@link MutableClock}, which the script
 * receives as an argument - that is what makes the throttle, the force-write window and the spoof
 * check testable without sleeping.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LocationIngestIntegrationTest.Clocks.class})
class LocationIngestIntegrationTest {

  /** Inside the configured Bengaluru box. */
  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);

  private static final Instant START = Instant.parse("2026-09-29T10:00:00Z");
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

  private UUID driverId;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    driverId = UUID.randomUUID();
  }

  /** Puts the driver on the map as an available car, the way DriverStatusChanged would. */
  private void driverIsAvailableCar() {
    store.applyStatusChange(driverId, DriverStatus.AVAILABLE.name(), VehicleType.CAR, null);
  }

  private LocationUpdate at(GeoPoint point, long seq) {
    return new LocationUpdate(
        point.latitude(), point.longitude(), 10.0, null, null, seq, CLOCK.instant());
  }

  private Long availableCount(VehicleType type) {
    return redis.opsForZSet().size("drivers:available:" + type.name());
  }

  @Nested
  class StatelessValidation {

    @Test
    void rejectsCoordinatesOutOfRange() {
      driverIsAvailableCar();

      assertThat(ingest.accept(driverId, new LocationUpdate(91, 77.6, 10, null, null, 1, START)))
          .isEqualTo("rejected_invalid");
      assertThat(ingest.accept(driverId, new LocationUpdate(12.9, -181, 10, null, null, 2, START)))
          .isEqualTo("rejected_invalid");

      // Nothing may be written for a rejected position, or the next update's speed
      // check would be measured from a coordinate we refused to believe.
      assertThat(redis.opsForHash().entries("driver:state:" + driverId)).doesNotContainKey("lat");
      assertThat(availableCount(VehicleType.CAR)).isZero();
    }

    @Test
    void rejectsNonFiniteCoordinates() {
      driverIsAvailableCar();

      assertThat(
              ingest.accept(
                  driverId, new LocationUpdate(Double.NaN, 77.6, 10, null, null, 1, START)))
          .isEqualTo("rejected_invalid");
      assertThat(
              ingest.accept(
                  driverId,
                  new LocationUpdate(12.9, Double.POSITIVE_INFINITY, 10, null, null, 2, START)))
          .isEqualTo("rejected_invalid");
    }

    @Test
    void ignoresAFixTooImpreciseToTrust() {
      driverIsAvailableCar();

      // max-accuracy-meters is 100.
      assertThat(
              ingest.accept(
                  driverId, new LocationUpdate(12.9752, 77.6064, 250, null, null, 1, START)))
          .isEqualTo("ignored_low_accuracy");
      assertThat(availableCount(VehicleType.CAR)).isZero();
    }
  }

  @Nested
  class AcceptedUpdates {

    @Test
    void putsAnAvailableDriverOnTheMapAtTheRightCoordinates() {
      driverIsAvailableCar();

      assertThat(ingest.accept(driverId, at(MG_ROAD, 1))).isEqualTo("accepted");

      var positions = redis.opsForGeo().position("drivers:available:CAR", driverId.toString());
      assertThat(positions).isNotNull().hasSize(1);
      var point = positions.getFirst();
      // Redis GEO takes longitude first. Swapping them is the classic mistake here and
      // would silently place every driver in the wrong hemisphere.
      assertThat(point.getX())
          .isCloseTo(MG_ROAD.longitude(), org.assertj.core.data.Offset.offset(1e-4));
      assertThat(point.getY())
          .isCloseTo(MG_ROAD.latitude(), org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void recordsTheDriverAsRecentlySeen() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      Double score = redis.opsForZSet().score("drivers:lastseen", driverId.toString());
      assertThat(score).isEqualTo((double) CLOCK.millis());
    }
  }

  @Nested
  class Ordering {

    @Test
    void ignoresADuplicateSequenceNumber() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 5));

      assertThat(ingest.accept(driverId, at(MG_ROAD, 5))).isEqualTo("ignored_out_of_order");
    }

    @Test
    void ignoresAPacketThatArrivedLate() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 5));
      CLOCK.advance(Duration.ofSeconds(5));

      // Seq 3 left the phone before seq 5 but arrived after it; the newer position is
      // the better answer.
      assertThat(ingest.accept(driverId, at(MG_ROAD, 3))).isEqualTo("ignored_out_of_order");
    }
  }

  @Nested
  class Throttling {

    @Test
    void throttlesASecondUpdateInsideTheMinimumInterval() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      // min-interval-ms is 1000.
      CLOCK.advance(Duration.ofMillis(200));

      assertThat(ingest.accept(driverId, at(new GeoPoint(12.9800, 77.6100), 2)))
          .isEqualTo("throttled");
    }

    @Test
    void stillCountsAThrottledUpdateAsASignOfLife() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofMillis(200));
      ingest.accept(driverId, at(MG_ROAD, 2));

      // The driver is plainly there, so the stale sweep must not come for them.
      assertThat(redis.opsForZSet().score("drivers:lastseen", driverId.toString()))
          .isEqualTo((double) CLOCK.millis());
    }

    @Test
    void acceptsOnceTheIntervalHasPassed() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofMillis(1500));

      // About 50 m: at 150 km/h a car covers 62 m in 1.5 s, so this is fast but
      // possible. A bigger step here would be caught by the spoof check instead,
      // which is what this test would then actually be measuring.
      assertThat(ingest.accept(driverId, at(new GeoPoint(12.97565, 77.6064), 2)))
          .isEqualTo("accepted");
    }
  }

  @Nested
  class SpoofDetection {

    /** About 5 km from MG Road. */
    private static final GeoPoint FAR_AWAY = new GeoPoint(13.0200, 77.6064);

    @Test
    void rejectsAJumpNoVehicleCouldHaveMade() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      // 5 km in 3 seconds is 6,000 km/h; max-speed-kmph is 150.
      CLOCK.advance(Duration.ofSeconds(3));

      assertThat(ingest.accept(driverId, at(FAR_AWAY, 2))).isEqualTo("rejected_spoof");
    }

    @Test
    void keepsTheLastBelievablePositionAfterASpoof() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofSeconds(3));
      ingest.accept(driverId, at(FAR_AWAY, 2));

      // Storing the jump would teleport the driver and make the next speed check
      // meaningless too.
      assertThat(store.lastKnownPosition(driverId))
          .get()
          .extracting(GeoPoint::latitude)
          .isEqualTo(MG_ROAD.latitude());
    }

    @Test
    void acceptsAFastButPossibleMove() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      // Roughly 1 km in 60 seconds: 60 km/h, a normal car on a main road.
      CLOCK.advance(Duration.ofSeconds(60));

      assertThat(ingest.accept(driverId, at(new GeoPoint(12.9842, 77.6064), 2)))
          .isEqualTo("accepted");
    }
  }

  @Nested
  class GeoWriteThrottling {

    @Test
    void skipsTheGeoWriteForADriverWhoBarelyMoved() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      Double firstWrite =
          (Double) toDouble(redis.opsForHash().get("driver:state:" + driverId, "lastGeoWriteTs"));

      // min-move-meters is 10; this is about 3 m.
      CLOCK.advance(Duration.ofSeconds(2));
      ingest.accept(driverId, at(new GeoPoint(12.97523, 77.6064), 2));

      Double secondWrite =
          (Double) toDouble(redis.opsForHash().get("driver:state:" + driverId, "lastGeoWriteTs"));
      assertThat(secondWrite).isEqualTo(firstWrite);
    }

    @Test
    void stillRefreshesLastSeenWhenTheGeoWriteIsSkipped() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofSeconds(2));
      ingest.accept(driverId, at(new GeoPoint(12.97523, 77.6064), 2));

      assertThat(redis.opsForZSet().score("drivers:lastseen", driverId.toString()))
          .isEqualTo((double) CLOCK.millis());
    }

    @Test
    void writesAgainOnceTheForceWindowHasPassed() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      Double firstWrite =
          (Double) toDouble(redis.opsForHash().get("driver:state:" + driverId, "lastGeoWriteTs"));

      // force-write-seconds is 15: a stationary driver is refreshed anyway, so the
      // set cannot quietly go stale.
      CLOCK.advance(Duration.ofSeconds(20));
      ingest.accept(driverId, at(new GeoPoint(12.97523, 77.6064), 2));

      Double secondWrite =
          (Double) toDouble(redis.opsForHash().get("driver:state:" + driverId, "lastGeoWriteTs"));
      assertThat(secondWrite).isGreaterThan(firstWrite);
    }

    private static Double toDouble(Object raw) {
      return raw == null ? null : Double.valueOf(String.valueOf(raw));
    }
  }

  @Nested
  class Availability {

    @Test
    void neverPlacesAnOfflineDriverOnTheMap() {
      store.applyStatusChange(driverId, DriverStatus.OFFLINE.name(), VehicleType.CAR, null);

      assertThat(ingest.accept(driverId, at(MG_ROAD, 1))).isEqualTo("accepted");
      assertThat(availableCount(VehicleType.CAR)).isZero();
    }

    @Test
    void neverPlacesADriverOnATripOnTheMap() {
      store.applyStatusChange(driverId, DriverStatus.ON_TRIP.name(), VehicleType.CAR, null);

      assertThat(ingest.accept(driverId, at(MG_ROAD, 1))).isEqualTo("accepted");
      // Being offered a second trip while driving the first is the failure this
      // prevents.
      assertThat(availableCount(VehicleType.CAR)).isZero();
    }

    @Test
    void removesADriverWhoLeavesTheServiceArea() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      assertThat(availableCount(VehicleType.CAR)).isOne();

      CLOCK.advance(Duration.ofMinutes(30));
      // Just north of the box: about 19 km in half an hour, or 39 km/h. Driving to
      // Mumbai instead would be rejected as a spoof, and this test would be measuring
      // the speed check rather than the service-area rule.
      assertThat(ingest.accept(driverId, at(new GeoPoint(13.1500, 77.6064), 2)))
          .isEqualTo("outside_service_area");
      assertThat(availableCount(VehicleType.CAR)).isZero();
    }

    @Test
    void putsThemBackWhenTheyReturn() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofMinutes(30));
      ingest.accept(driverId, at(new GeoPoint(13.1500, 77.6064), 2));

      CLOCK.advance(Duration.ofMinutes(30));
      assertThat(ingest.accept(driverId, at(MG_ROAD, 3))).isEqualTo("accepted");
      assertThat(availableCount(VehicleType.CAR)).isOne();
    }
  }

  @Nested
  class ScriptCache {

    @Test
    void survivesRedisLosingItsScriptCache() {
      driverIsAvailableCar();
      assertThat(ingest.accept(driverId, at(MG_ROAD, 1))).isEqualTo("accepted");

      // What a Redis restart or an operator's SCRIPT FLUSH does. Calling by SHA alone
      // would fail every update from here until redeploy; the EVAL fallback is the
      // only thing that makes EVALSHA safe to rely on.
      redis.getConnectionFactory().getConnection().scriptingCommands().scriptFlush();

      CLOCK.advance(Duration.ofSeconds(5));
      // About 100 m in 5 s: 72 km/h, an ordinary road speed.
      assertThat(ingest.accept(driverId, at(new GeoPoint(12.97610, 77.6064), 2)))
          .isEqualTo("accepted");
    }
  }
}
