package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverFlagging;
import com.rideflow.driver.DriverStatus;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.TripStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

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
  @Autowired private CircuitBreakerRegistry breakers;
  @Autowired private com.rideflow.shared.RideFlowProperties properties;
  @MockitoSpyBean private DriverFlagging flagging;

  private UUID driverId;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    driverId = UUID.randomUUID();
  }

  /**
   * Redis away - its breaker open after a run of timeouts: positions are dropped at once and
   * counted, not queued behind one-second timeouts. Closed again, they are taken as before.
   */
  @Test
  void dropsPositionsAtOnceWhileRedisIsUnavailable() {
    CircuitBreaker breaker = breakers.circuitBreaker("redis-location");
    breaker.transitionToForcedOpenState();
    try {
      assertThat(ingest.accept(driverId, at(MG_ROAD, 1))).isEqualTo("unavailable");
      assertThat(redis.hasKey("driver:state:" + driverId)).isFalse();
    } finally {
      breaker.transitionToClosedState();
    }

    assertThat(ingest.accept(driverId, at(MG_ROAD, 2))).isEqualTo("accepted");
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

    /** A reconnect is not a fresh start: a spoofing app must not teleport by reconnecting. */
    @Test
    void judgesTheFirstPositionAfterAReconnectLikeAnyOther() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 41));
      CLOCK.advance(Duration.ofSeconds(3));

      store.restartSequence(driverId); // What a new STOMP CONNECT does.

      // About 5 km in 3 s.
      assertThat(ingest.accept(driverId, at(FAR_AWAY, 1))).isEqualTo("rejected_spoof");
    }

    /**
     * A driver who drove with the app off is placed where they reconnect: the time since their last
     * position is in the division, so the distance reads as an ordinary speed.
     */
    @Test
    void placesADriverWhoDroveWithTheAppOffWhereTheyReconnect() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 41));
      CLOCK.advance(Duration.ofMinutes(10));

      store.restartSequence(driverId);

      // About 5 km in 10 minutes: 30 km/h.
      assertThat(ingest.accept(driverId, at(FAR_AWAY, 1))).isEqualTo("accepted");
    }

    /** Persistent jumps flag the driver for review once - not on every jump after. */
    @Test
    void flagsTheDriverOnceWhenTheJumpsPersist() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      int threshold = properties.location().spoofStrikesBeforeFlag();
      for (int jump = 0; jump < 2 * threshold - 1; jump++) {
        CLOCK.advance(Duration.ofSeconds(3));
        assertThat(ingest.accept(driverId, at(FAR_AWAY, jump + 2))).isEqualTo("rejected_spoof");
      }

      verify(flagging, times(1)).flagForReview(eq(driverId), anyString());
    }

    /** A flag the database did not take is tried again, and never breaks the position stream. */
    @Test
    void triesTheFlagAgainWhenTheDatabaseDidNotTakeIt() {
      doThrow(new CannotCreateTransactionException("Connection is not available"))
          .doCallRealMethod()
          .when(flagging)
          .flagForReview(eq(driverId), anyString());
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));
      int threshold = properties.location().spoofStrikesBeforeFlag();
      for (int jump = 0; jump < 2 * threshold; jump++) {
        CLOCK.advance(Duration.ofSeconds(3));
        assertThat(ingest.accept(driverId, at(FAR_AWAY, jump + 2))).isEqualTo("rejected_spoof");
      }

      verify(flagging, times(2)).flagForReview(eq(driverId), anyString());
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

  /** {@code trip}'s view, for the arrival geofence: where the driver is, if recently heard from. */
  @Nested
  class CurrentPosition {

    @Test
    void isTheLastAcceptedPositionWhileFresh() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      CLOCK.advance(Duration.ofSeconds(29));

      assertThat(store.currentPosition(driverId)).contains(MG_ROAD);
    }

    @Test
    void isEmptyOnceAsOldAsTheStaleSweepCutoff() {
      driverIsAvailableCar();
      ingest.accept(driverId, at(MG_ROAD, 1));

      // The sweep takes a driver off the map at exactly this age; a position it would
      // discard must not count for arrival either.
      CLOCK.advance(Duration.ofSeconds(30));

      assertThat(store.currentPosition(driverId)).isEmpty();
    }

    @Test
    void isEmptyForADriverNeverHeardFrom() {
      assertThat(store.currentPosition(driverId)).isEmpty();
    }
  }

  /** A driver on a trip: the rider sees them coming, the route is recorded, silence is told. */
  @Nested
  class OnATrip {

    @Autowired RedisConnectionFactory connections;
    @Autowired StaleDriverSweeper sweeper;
    @Autowired JsonMapper json;

    private final UUID tripId = UUID.randomUUID();
    private final UUID riderId = UUID.randomUUID();
    private final List<Map<String, Object>> published = new CopyOnWriteArrayList<>();
    private RedisMessageListenerContainer listener;

    /** 1 km due north of MG Road. */
    private final GeoPoint pickup =
        new GeoPoint(MG_ROAD.latitude() + 0.008993, MG_ROAD.longitude());

    private final GeoPoint drop = new GeoPoint(13.0352, 77.6064);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void listenToEverythingPublished() {
      listener = new RedisMessageListenerContainer();
      listener.setConnectionFactory(connections);
      listener.addMessageListener(
          (message, pattern) -> published.add(json.readValue(message.getBody(), Map.class)),
          new ChannelTopic("rt:events"));
      listener.afterPropertiesSet();
      listener.start();
      // Pub/Sub keeps nothing for a late subscriber: wait until this one hears a probe.
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () -> {
                // Well formed, for nobody connected: every instance's delivery drops it quietly.
                redis.convertAndSend(
                    "rt:events",
                    "{\"userId\":\""
                        + UUID.randomUUID()
                        + "\",\"destination\":\"/queue/probe\",\"envelope\":\"{}\"}");
                return !published.isEmpty();
              });
      published.clear();
    }

    @AfterEach
    void stopListening() throws Exception {
      listener.destroy();
    }

    private void onTrip(TripStatus status) {
      store.applyStatusChange(driverId, DriverStatus.ON_TRIP.name(), VehicleType.CAR, null);
      store.applyTripContext(
          driverId, Optional.of(new ActiveTrip(tripId, riderId, status, pickup, drop)));
    }

    /** What reached the rider on {@code destination}, as envelopes. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRider(String destination) {
      return published.stream()
          .filter(m -> riderId.toString().equals(m.get("userId")))
          .filter(m -> destination.equals(m.get("destination")))
          .map(m -> (Map<String, Object>) json.readValue((String) m.get("envelope"), Map.class))
          .toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsTheRiderWhereTheirDriverIsAtMostEveryTwoSeconds() {
      onTrip(TripStatus.DRIVER_ASSIGNED);

      ingest.accept(driverId, at(MG_ROAD, 1));
      CLOCK.advance(Duration.ofSeconds(1));
      ingest.accept(
          driverId, at(new GeoPoint(MG_ROAD.latitude() + 0.0001, MG_ROAD.longitude()), 2));
      CLOCK.advance(Duration.ofSeconds(1));
      ingest.accept(
          driverId, at(new GeoPoint(MG_ROAD.latitude() + 0.0002, MG_ROAD.longitude()), 3));

      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> toRider("/queue/driver-location").size() == 2);
      Map<String, Object> first = toRider("/queue/driver-location").getFirst();
      assertThat(first)
          .containsEntry("type", "DRIVER_LOCATION")
          .containsEntry("tripId", tripId.toString())
          .containsEntry("seq", 1);
      Map<String, Object> position = (Map<String, Object>) first.get("payload");
      // 1 km straight, 1.3 km by road, at 22 km/h: 213 s to the pickup.
      assertThat(position).containsEntry("phase", "TO_PICKUP").containsEntry("etaSeconds", 213);
    }

    @Test
    void recordsTheRouteOnlyOnceTheRiderIsAboard() {
      onTrip(TripStatus.DRIVER_ASSIGNED);
      ingest.accept(driverId, at(MG_ROAD, 1));
      assertThat(redis.hasKey("trip:route:" + tripId)).isFalse();

      onTrip(TripStatus.IN_PROGRESS);
      CLOCK.advance(Duration.ofSeconds(5));
      ingest.accept(
          driverId, at(new GeoPoint(MG_ROAD.latitude() + 0.0005, MG_ROAD.longitude()), 2));
      CLOCK.advance(Duration.ofSeconds(5));
      ingest.accept(
          driverId, at(new GeoPoint(MG_ROAD.latitude() + 0.0010, MG_ROAD.longitude()), 3));

      assertThat(redis.opsForList().size("trip:route:" + tripId)).isEqualTo(2);
      assertThat(redis.getExpire("trip:route:" + tripId)).isPositive();
    }

    @Test
    void tellsTheRiderWhenTheirDriverGoesQuiet() {
      onTrip(TripStatus.IN_PROGRESS);
      ingest.accept(driverId, at(MG_ROAD, 1));

      CLOCK.advance(Duration.ofSeconds(30));
      sweeper.sweep();

      await().atMost(Duration.ofSeconds(10)).until(() -> !toRider("/queue/trip-updates").isEmpty());
      assertThat(toRider("/queue/trip-updates").getFirst())
          .containsEntry("type", "DRIVER_CONNECTION_LOST")
          .containsEntry("tripId", tripId.toString());
    }

    /** The context and the hash's expiry in one step, even on a hash that did not exist. */
    @Test
    void neverLeavesTheStateWithoutAnExpiry() {
      UUID fresh = UUID.randomUUID();

      store.applyTripContext(
          fresh,
          Optional.of(new ActiveTrip(tripId, riderId, TripStatus.DRIVER_ASSIGNED, pickup, drop)));

      assertThat(redis.opsForHash().get("driver:state:" + fresh, "tripPhase"))
          .isEqualTo("TO_PICKUP");
      assertThat(redis.getExpire("driver:state:" + fresh)).isPositive();
    }

    @Test
    void forgetsTheTripOnceItIsOver() {
      onTrip(TripStatus.IN_PROGRESS);
      store.applyTripContext(driverId, Optional.empty());

      ingest.accept(driverId, at(MG_ROAD, 1));

      assertThat(redis.hasKey("trip:route:" + tripId)).isFalse();
      assertThat(redis.opsForHash().get("driver:state:" + driverId, "tripId")).isNull();
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
