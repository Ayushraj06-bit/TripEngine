package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.identity.Role;
import com.rideflow.location.internal.LocationIngestService;
import com.rideflow.location.internal.LocationUpdate;
import com.rideflow.pricing.TripFees;
import com.rideflow.shared.GeoPoint;
import com.rideflow.trip.TripStatus;
import com.rideflow.trip.internal.TripTestConfiguration.RecordedPushes.Push;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A trip while it happens: who is told what, the route it takes, what it costs, who reviews it. */
class LiveTripIntegrationTest extends TripIntegrationTestBase {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);
  private static final String TRIP_UPDATES = "/queue/trip-updates";
  private static final TripTestConfiguration.RecordedPushes PUSHES = TripTestConfiguration.PUSHES;

  @Autowired LocationIngestService ingest;
  @Autowired StringRedisTemplate redis;
  @Autowired SilentDriverCheck silenceCheck;
  @Autowired TripFees tripFees;

  private final AtomicLong sequence = new AtomicLong();

  @AfterEach
  void removeTestRateCards() {
    jdbc.update("DELETE FROM rate_cards WHERE version > 1");
  }

  private List<String> updateTypes(User user) {
    return PUSHES.to(user.id(), TRIP_UPDATES).stream().map(push -> push.message().type()).toList();
  }

  private Object stateOf(User driver, String field) {
    return redis.opsForHash().get("driver:state:" + driver.id(), field);
  }

  /** The driver's phone reporting a position, the way the WebSocket would deliver it. */
  private String report(User driver, GeoPoint position) {
    return ingest.accept(
        driver.id(),
        new LocationUpdate(
            position.latitude(),
            position.longitude(),
            10,
            null,
            null,
            sequence.incrementAndGet(),
            CLOCK.instant()));
  }

  private static GeoPoint northOfPickup(double meters) {
    return new GeoPoint(
        MG_ROAD.latitude() + Math.toDegrees(meters / 6_371_008.8), MG_ROAD.longitude());
  }

  /** Booked, assigned, arrived and started - and the location module has caught up. */
  private UUID tripUnderWay(User rider, User driver) throws Exception {
    UUID tripId = assignedTrip(rider, driver);
    trips.arrive(tripId, driver.asDriver());
    trips.start(tripId, driver.asDriver(), otpOf(tripId));
    await().atMost(EVENTUALLY).until(() -> "TO_DROP".equals(stateOf(driver, "tripPhase")));
    return tripId;
  }

  @Nested
  class TripUpdates {

    @Test
    void tellsTheRiderAndTheDriverOfEveryChangeAndNobodyElse() throws Exception {
      User rider = rider();
      User driver = driver();
      User bystander = rider();

      tripUnderWay(rider, driver);

      await()
          .atMost(EVENTUALLY)
          .until(
              () ->
                  updateTypes(rider)
                      .containsAll(
                          List.of(
                              "TripRequested", "DriverAssigned", "DriverArrived", "TripStarted")));
      await()
          .atMost(EVENTUALLY)
          .until(
              () ->
                  updateTypes(driver)
                      .containsAll(List.of("DriverAssigned", "DriverArrived", "TripStarted")));
      assertThat(PUSHES.all()).noneMatch(push -> push.userId().equals(bystander.id()));
    }

    @Test
    void showsTheOtpOnlyInTheRidersCopy() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);

      await().atMost(EVENTUALLY).until(() -> updateTypes(driver).contains("DriverAssigned"));
      await().atMost(EVENTUALLY).until(() -> updateTypes(rider).contains("DriverAssigned"));

      assertThat(assigned(rider).otp()).isEqualTo(otpOf(tripId));
      assertThat(assigned(driver).otp()).isNull();
    }

    private TripView assigned(User user) {
      return PUSHES.to(user.id(), TRIP_UPDATES).stream()
          .filter(push -> push.message().type().equals("DriverAssigned"))
          .map(push -> (TripView) push.message().payload())
          .findFirst()
          .orElseThrow();
    }
  }

  @Nested
  class TheRouteAndTheFare {

    /**
     * Rates go up mid-trip and the driver takes a detour 40% longer than the estimate: the trip is
     * repriced for the route driven, with the rate card it was booked on, and the rider watched the
     * whole way.
     */
    @Test
    void repricesALongDetourWithTheCardTheTripWasBookedOn() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = tripUnderWay(rider, driver);
      long estimated =
          jdbc.queryForObject("SELECT distance_m FROM trips WHERE id = ?", Long.class, tripId);

      CLOCK.advance(Duration.ofSeconds(1));
      UUID dearer = UUID.randomUUID();
      jdbc.update(
          """
          INSERT INTO rate_cards (id, vehicle_type, version, base_paise, per_km_paise,
              per_min_paise, min_fare_paise, waiting_per_min_paise, cancellation_fee_paise,
              effective_from)
          VALUES (?, 'CAR', 2, 9000, 2600, 400, 12000, 500, 7000, ?)""",
          dearer,
          utc(CLOCK.instant()));

      // 100 m every 10 s: 36 km/h, an ordinary city speed.
      long driven = estimated * 14 / 10;
      int points = 0;
      for (long meters = 0; meters <= driven; meters += 100, points++) {
        CLOCK.advance(Duration.ofSeconds(10));
        assertThat(report(driver, northOfPickup(meters))).isEqualTo("accepted");
      }
      assertThat(redis.opsForList().size("trip:route:" + tripId)).isEqualTo(points);

      TripView completed = trips.complete(tripId, driver.asDriver());

      long distance =
          jdbc.queryForObject("SELECT distance_m FROM trips WHERE id = ?", Long.class, tripId);
      long duration =
          jdbc.queryForObject("SELECT duration_s FROM trips WHERE id = ?", Long.class, tripId);
      assertThat(distance).isCloseTo((driven / 100) * 100, within(2L));
      long bookedRates =
          tripFees.fareFor(CAR_RATE_CARD, distance, duration, BigDecimal.ONE).total().paise();
      assertThat(completed.finalFarePaise()).isEqualTo(bookedRates);
      assertThat(bookedRates)
          .isNotEqualTo(
              tripFees.fareFor(dearer, distance, duration, BigDecimal.ONE).total().paise());
      assertThat(redis.hasKey("trip:route:" + tripId)).isFalse();

      List<Push> positions = PUSHES.to(rider.id(), "/queue/driver-location");
      assertThat(positions).isNotEmpty();
      assertThat(positions.getLast().message().tripId()).isEqualTo(tripId);
    }

    @Test
    void completesAtTheQuoteAndFlagsTheTripWhenTheRouteCannotBeRead() throws Exception {
      User driver = driver();
      UUID tripId = tripUnderWay(rider(), driver);
      routes.breakReads();

      TripView completed = trips.complete(tripId, driver.asDriver());

      assertThat(completed.status()).isEqualTo(TripStatus.COMPLETED);
      assertThat(completed.finalFarePaise()).isEqualTo(completed.quotedFarePaise());
      assertThat(flaggedReason(tripId)).isEqualTo("No route was recorded; charged the quoted fare");
    }
  }

  @Nested
  class ASilentDriver {

    @Test
    void isFlaggedAfterTenMinutesOfSilenceAndTheTripCarriesOn() {
      User driver = userRow(Role.DRIVER);
      UUID tripId = tripIn(TripStatus.IN_PROGRESS, userRow(Role.RIDER), driver);
      TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);

      CLOCK.advance(Duration.ofMinutes(9));
      silenceCheck.check();
      assertThat(flaggedReason(tripId)).isNull();

      CLOCK.advance(Duration.ofMinutes(2));
      silenceCheck.check();
      assertThat(flaggedReason(tripId))
          .isEqualTo("Driver silent for over 10 minutes during the trip");
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.IN_PROGRESS);
    }

    @Test
    void isNotFlaggedWhileTheDriverKeepsReporting() {
      User driver = userRow(Role.DRIVER);
      UUID tripId = tripIn(TripStatus.IN_PROGRESS, userRow(Role.RIDER), driver);

      CLOCK.advance(Duration.ofMinutes(5));
      TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);
      CLOCK.advance(Duration.ofMinutes(6));
      silenceCheck.check();

      assertThat(flaggedReason(tripId)).isNull();
    }

    /** More silent trips than one window holds (two, in tests): every one is still found. */
    @Test
    void checksEveryTripInProgressNotOnlyTheFirstWindow() {
      List<UUID> silent = new java.util.ArrayList<>();
      for (int i = 0; i < 5; i++) {
        silent.add(tripIn(TripStatus.IN_PROGRESS, userRow(Role.RIDER), userRow(Role.DRIVER)));
      }

      CLOCK.advance(Duration.ofMinutes(11));
      silenceCheck.check();

      assertThat(silent).allSatisfy(tripId -> assertThat(flaggedReason(tripId)).isNotNull());
    }
  }

  private String flaggedReason(UUID tripId) {
    return jdbc.queryForObject(
        "SELECT flagged_reason FROM trips WHERE id = ?", String.class, tripId);
  }

  @Nested
  class FlaggedTripsForAdmins {

    @Test
    void listsFlaggedTripsNewestFirstAndOnlyThose() throws Exception {
      String admin =
          auth.register("+919999900001", "Admin", "correct-horse-battery", Role.ADMIN)
              .accessToken();
      UUID older = bookedTrip(rider());
      CLOCK.advance(Duration.ofSeconds(1));
      UUID newer = bookedTrip(rider());
      CLOCK.advance(Duration.ofSeconds(1));
      bookedTrip(rider());
      trips.flag(older, "First");
      trips.flag(newer, "Second");

      String first =
          mockMvc
              .perform(
                  get("/api/v1/admin/trips/flagged?limit=1")
                      .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.trips.length()").value(1))
              .andExpect(jsonPath("$.trips[0].tripId").value(newer.toString()))
              .andExpect(jsonPath("$.trips[0].flaggedReason").value("Second"))
              .andReturn()
              .getResponse()
              .getContentAsString();

      mockMvc
          .perform(
              get("/api/v1/admin/trips/flagged?limit=1&cursor="
                      + JsonPath.read(first, "$.nextCursor"))
                  .header("Authorization", "Bearer " + admin))
          .andExpect(jsonPath("$.trips.length()").value(1))
          .andExpect(jsonPath("$.trips[0].tripId").value(older.toString()))
          .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void keepsTheFirstReason() {
      UUID tripId = tripIn(TripStatus.COMPLETED, userRow(Role.RIDER), userRow(Role.DRIVER));

      assertThat(trips.flag(tripId, "First")).isTrue();
      assertThat(trips.flag(tripId, "Second")).isFalse();
    }
  }
}
