package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.driver.DriverStatus;
import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripReturnedToMatching;
import com.rideflow.trip.TripStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/** The trip lifecycle end to end, and the rules around each step (PROJECT_SPEC section 6). */
@RecordApplicationEvents
class TripLifecycleIntegrationTest extends TripIntegrationTestBase {

  /** The CAR card's cancellation fee, in paise. */
  private static final long CAR_CANCELLATION_FEE = 5000;

  @Autowired ApplicationEvents events;

  private ResultActions act(User user, String action, UUID tripId, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/trips/{id}/" + action, tripId)
            .header("Authorization", "Bearer " + user.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions cancel(User user, UUID tripId) throws Exception {
    return act(user, "cancel", tripId, "{\"reason\":\"Plans changed\"}");
  }

  private ResultActions start(User driver, UUID tripId, String otp) throws Exception {
    return act(driver, "start", tripId, "{\"otp\":\"%s\"}".formatted(otp));
  }

  private ResultActions view(User user, String path) throws Exception {
    return mockMvc.perform(get(path).header("Authorization", "Bearer " + user.token()));
  }

  private DriverStatus driverStatus(User driver) {
    return DriverStatus.valueOf(
        jdbc.queryForObject(
            "SELECT status FROM drivers WHERE user_id = ?", String.class, driver.id()));
  }

  private void awaitDriver(User driver, DriverStatus expected) {
    await().atMost(Duration.ofSeconds(10)).until(() -> driverStatus(driver) == expected);
  }

  /** A point {@code meters} due north of the pickup. */
  private static GeoPoint northOfPickup(double meters) {
    return new GeoPoint(
        MG_ROAD.latitude() + Math.toDegrees(meters / 6_371_008.8), MG_ROAD.longitude());
  }

  private UUID arrivedTrip(User rider, User driver) throws Exception {
    UUID tripId = assignedTrip(rider, driver);
    trips.arrive(tripId, driver.asDriver());
    return tripId;
  }

  private static void assertRefused(Runnable call, String code, HttpStatus status) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(call::run)
        .satisfies(
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.status()).isEqualTo(status);
            });
  }

  @Nested
  class Cancelling {

    @Test
    void cancellingTwiceAnswersWithTheCurrentStateInsteadOfAnError() throws Exception {
      User rider = rider();
      UUID tripId = bookedTrip(rider);

      cancel(rider, tripId)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CANCELLED"));
      cancel(rider, tripId)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CANCELLED"));

      // Booking and one cancellation: the repeat recorded nothing.
      assertThat(auditRows(tripId)).isEqualTo(2);
    }

    @Test
    void refusesToCancelACompletedTrip() throws Exception {
      User rider = rider();
      UUID tripId = tripIn(TripStatus.COMPLETED, rider, userRow(Role.DRIVER));

      cancel(rider, tripId)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("TRIP_INVALID_TRANSITION"))
          .andExpect(jsonPath("$.tripStatus").value("COMPLETED"));
    }

    @Test
    void isFreeBeforeADriverIsAssigned() throws Exception {
      User rider = rider();
      UUID tripId = bookedTrip(rider);

      assertThat(trips.cancel(tripId, rider.asRider(), "Plans changed").cancellationFeePaise())
          .isZero();
    }

    @Test
    void isFreeUpToTheEndOfTheGracePeriodAfterAssignment() throws Exception {
      User rider = rider();
      UUID tripId = assignedTrip(rider, driver());
      CLOCK.advance(Duration.ofSeconds(120));

      TripView cancelled = trips.cancel(tripId, rider.asRider(), "Plans changed");

      assertThat(cancelled.cancellationFeePaise()).isZero();
      assertThat(cancelled.cancelledBy()).isEqualTo(TripActor.RIDER);
    }

    @Test
    void chargesTheRateCardFeeOnceTheGracePeriodIsOver() throws Exception {
      User rider = rider();
      UUID tripId = assignedTrip(rider, driver());
      CLOCK.advance(Duration.ofSeconds(121));

      assertThat(trips.cancel(tripId, rider.asRider(), "Plans changed").cancellationFeePaise())
          .isEqualTo(CAR_CANCELLATION_FEE);
    }

    @Test
    void chargesTheFeeOnceTheDriverHasArrived() throws Exception {
      User rider = rider();
      UUID tripId = arrivedTrip(rider, driver());

      assertThat(trips.cancel(tripId, rider.asRider(), "Plans changed").cancellationFeePaise())
          .isEqualTo(CAR_CANCELLATION_FEE);
    }

    @Test
    void aDriverBackingOutReturnsTheTripToMatchingAndIsFreedForTheNext() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);
      awaitDriver(driver, DriverStatus.ON_TRIP);

      cancel(driver, tripId)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("REQUESTED"))
          .andExpect(jsonPath("$.driverId").doesNotExist());

      assertThat(events.stream(TripReturnedToMatching.class))
          .singleElement()
          .satisfies(event -> assertThat(event.previousDriverId()).isEqualTo(driver.id()));
      assertThat(otpOf(tripId)).isNull();
      awaitDriver(driver, DriverStatus.AVAILABLE);
    }

    @Test
    void letsADriverDeclareANoShowOnlyAfterWaitingTheFullTime() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = arrivedTrip(rider, driver);

      CLOCK.advance(Duration.ofMinutes(5).minusSeconds(1));
      assertRefused(
          () -> trips.cancel(tripId, driver.asDriver(), "Rider did not come"),
          "NO_SHOW_TOO_EARLY",
          HttpStatus.UNPROCESSABLE_CONTENT);

      CLOCK.advance(Duration.ofSeconds(1));
      TripView cancelled = trips.cancel(tripId, driver.asDriver(), "Rider did not come");

      assertThat(cancelled.status()).isEqualTo(TripStatus.CANCELLED);
      assertThat(cancelled.cancelledBy()).isEqualTo(TripActor.DRIVER);
      assertThat(cancelled.cancellationFeePaise()).isEqualTo(CAR_CANCELLATION_FEE);
      awaitDriver(driver, DriverStatus.AVAILABLE);
    }
  }

  @Nested
  class Arriving {

    @Test
    void refusesADriverFiveHundredMetresAway() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);
      TripTestConfiguration.POSITIONS.place(driver.id(), northOfPickup(500));

      act(driver, "arrive", tripId, "")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("NOT_AT_PICKUP"))
          .andExpect(jsonPath("$.distanceMeters").value(500));
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.DRIVER_ASSIGNED);
    }

    @Test
    void acceptsADriverOneHundredMetresAway() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);
      TripTestConfiguration.POSITIONS.place(driver.id(), northOfPickup(100));

      act(driver, "arrive", tripId, "")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DRIVER_ARRIVED"));
    }

    @Test
    void refusesADriverWhosePositionIsUnknown() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);
      TripTestConfiguration.POSITIONS.clear();

      act(driver, "arrive", tripId, "")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("POSITION_UNKNOWN"));
    }
  }

  @Nested
  class Starting {

    @Test
    void locksTheCodeAfterThreeWrongAttemptsUntilTheLockRunsOut() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = arrivedTrip(rider, driver);
      String otp = otpOf(tripId);
      String wrong = otp.equals("0000") ? "1111" : "0000";

      start(driver, tripId, wrong)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("OTP_INVALID"))
          .andExpect(jsonPath("$.attemptsRemaining").value(2));
      start(driver, tripId, wrong).andExpect(jsonPath("$.attemptsRemaining").value(1));
      start(driver, tripId, wrong)
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.code").value("OTP_LOCKED"))
          .andExpect(header().string("Retry-After", "120"));

      // The right code is refused while the lock holds...
      CLOCK.advance(Duration.ofMinutes(2).minusSeconds(1));
      start(driver, tripId, otp)
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.code").value("OTP_LOCKED"));

      // ...and accepted once it lifts.
      CLOCK.advance(Duration.ofSeconds(1));
      start(driver, tripId, otp)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
      assertThat(otpOf(tripId)).isNull();
    }

    @Test
    void refusesToStartBeforeTheDriverHasArrived() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);

      start(driver, tripId, otpOf(tripId))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("TRIP_INVALID_TRANSITION"));
    }
  }

  @Nested
  class Completing {

    @Test
    void completesAtTheQuotedFareAndFreesTheDriver() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = arrivedTrip(rider, driver);
      trips.start(tripId, driver.asDriver(), otpOf(tripId));
      // No route recorded here, and a ride longer than the short-trip threshold: the quote
      // stands. (Not so long that the driver's 15-minute token runs out.)
      CLOCK.advance(Duration.ofMinutes(2));

      String body =
          act(driver, "complete", tripId, "")
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn()
              .getResponse()
              .getContentAsString();

      long quoted = ((Number) JsonPath.read(body, "$.quotedFarePaise")).longValue();
      assertThat(((Number) JsonPath.read(body, "$.finalFarePaise")).longValue()).isEqualTo(quoted);
      awaitDriver(driver, DriverStatus.AVAILABLE);
    }

    @Test
    void putsTheDriverOnTripWhenAssigned() throws Exception {
      User rider = rider();
      User driver = driver();

      assignedTrip(rider, driver);

      assertThat(events.stream(DriverAssigned.class)).hasSize(1);
      awaitDriver(driver, DriverStatus.ON_TRIP);
    }
  }

  @Nested
  class Visibility {

    @Test
    void showsTheOtpToTheRiderAndNeverToTheDriver() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);

      view(rider, "/api/v1/trips/" + tripId)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.otp").value(otpOf(tripId)));
      view(driver, "/api/v1/trips/" + tripId)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.otp").doesNotExist());
      view(driver, "/api/v1/trips/active").andExpect(jsonPath("$.otp").doesNotExist());
    }

    @Test
    void hidesATripFromAnotherRider() throws Exception {
      UUID tripId = bookedTrip(rider());

      view(rider(), "/api/v1/trips/" + tripId)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void hidesATripFromADriverWhoIsNotOnIt() throws Exception {
      User rider = rider();
      UUID tripId = assignedTrip(rider, driver());
      User stranger = driver();
      TripTestConfiguration.POSITIONS.place(stranger.id(), MG_ROAD);

      act(stranger, "arrive", tripId, "").andExpect(status().isNotFound());
      view(stranger, "/api/v1/trips/" + tripId).andExpect(status().isNotFound());
    }

    @Test
    void answersNoContentWhenThereIsNoActiveTrip() throws Exception {
      view(rider(), "/api/v1/trips/active").andExpect(status().isNoContent());
    }

    @Test
    void findsTheActiveTripForItsRiderAndItsDriver() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);

      view(rider, "/api/v1/trips/active").andExpect(jsonPath("$.id").value(tripId.toString()));
      view(driver, "/api/v1/trips/active").andExpect(jsonPath("$.id").value(tripId.toString()));
    }

    @Test
    void pagesThroughHistoryNewestFirst() throws Exception {
      User rider = rider();
      List<UUID> booked = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        UUID tripId = bookedTrip(rider);
        trips.cancel(tripId, rider.asRider(), "Plans changed");
        booked.add(tripId);
        CLOCK.advance(Duration.ofSeconds(1));
      }

      String first =
          view(rider, "/api/v1/trips?limit=2")
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.trips.length()").value(2))
              .andExpect(jsonPath("$.trips[0].id").value(booked.get(2).toString()))
              .andExpect(jsonPath("$.trips[1].id").value(booked.get(1).toString()))
              .andReturn()
              .getResponse()
              .getContentAsString();

      view(rider, "/api/v1/trips?limit=2&cursor=" + JsonPath.read(first, "$.nextCursor"))
          .andExpect(jsonPath("$.trips.length()").value(1))
          .andExpect(jsonPath("$.trips[0].id").value(booked.get(0).toString()))
          .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void refusesAnotherUsersTripAsACursor() throws Exception {
      UUID someoneElses = bookedTrip(rider());

      view(rider(), "/api/v1/trips?cursor=" + someoneElses)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void capsThePageSizeAtFifty() throws Exception {
      view(rider(), "/api/v1/trips?limit=51").andExpect(status().isBadRequest());
    }
  }

  @Nested
  class SystemTransitions {

    @Test
    void assigningTheSameDriverAgainChangesNothing() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = assignedTrip(rider, driver);
      String otp = otpOf(tripId);

      trips.assignDriver(tripId, driver.id());

      assertThat(otpOf(tripId)).isEqualTo(otp);
      assertThat(auditRows(tripId)).isEqualTo(2);
    }

    @Test
    void refusesToAssignADriverWhoIsOnAnotherTrip() throws Exception {
      User driver = driver();
      assignedTrip(rider(), driver);
      UUID second = bookedTrip(rider());

      assertRefused(
          () -> trips.assignDriver(second, driver.id()),
          "DRIVER_HAS_ACTIVE_TRIP",
          HttpStatus.CONFLICT);
      assertThat(statusOf(second)).isEqualTo(TripStatus.REQUESTED);
    }

    @Test
    void givesUpOnATripOnceAndOnlyOnce() throws Exception {
      UUID tripId = bookedTrip(rider());

      trips.markNoDriverFound(tripId);
      trips.markNoDriverFound(tripId);

      assertThat(statusOf(tripId)).isEqualTo(TripStatus.NO_DRIVER_FOUND);
      assertThat(auditRows(tripId)).isEqualTo(2);
    }

    @Test
    void retriesOnceAfterAConflictAndThenGivesUp() {
      List<String> calls = new ArrayList<>();
      TripService.retryOnceOnConflict(
          () -> {
            calls.add("try");
            if (calls.size() == 1) {
              throw new OptimisticLockingFailureException("first");
            }
          });
      assertThat(calls).hasSize(2);

      assertThatExceptionOfType(OptimisticLockingFailureException.class)
          .isThrownBy(
              () ->
                  TripService.retryOnceOnConflict(
                      () -> {
                        throw new OptimisticLockingFailureException("always");
                      }))
          .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }
  }

  @Nested
  class Concurrency {

    /**
     * The rider's cancel reads the trip, then waits inside its transaction while the driver starts
     * it. Its write then finds a newer version: 409 with the state the trip is really in.
     */
    @Test
    void answersALostRaceWithTheTripsCurrentState() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = arrivedTrip(rider, driver);
      String otp = otpOf(tripId);

      CountDownLatch cancelHasReadTheTrip = fees.pauseNextLookup();
      try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
        Future<?> cancel =
            pool.submit(() -> trips.cancel(tripId, rider.asRider(), "Plans changed"));
        assertThat(cancelHasReadTheTrip.await(10, TimeUnit.SECONDS)).isTrue();

        trips.start(tripId, driver.asDriver(), otp);
        fees.resume();

        assertThatExceptionOfType(java.util.concurrent.ExecutionException.class)
            .isThrownBy(() -> cancel.get(10, TimeUnit.SECONDS))
            .havingCause()
            .isInstanceOfSatisfying(
                BusinessException.class,
                e -> {
                  assertThat(e.code()).isEqualTo("TRIP_CONCURRENT_UPDATE");
                  assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                  assertThat(e.properties()).containsEntry("tripStatus", TripStatus.IN_PROGRESS);
                });
      }
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.IN_PROGRESS);
    }

    /**
     * Fifty rounds of the rider cancelling at the moment the driver arrives. Whoever wins, the trip
     * ends in one valid state, and its audit log is a chain of transitions the table allows.
     */
    @Test
    void cancelRacingArrivalAlwaysEndsInOneValidState() throws Exception {
      User rider = userRow(Role.RIDER);
      User driver = userRow(Role.DRIVER);
      TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);

      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (int round = 0; round < 50; round++) {
          jdbc.execute("TRUNCATE trip_events, trips");
          UUID tripId = tripIn(TripStatus.DRIVER_ASSIGNED, rider, driver);
          CountDownLatch go = new CountDownLatch(1);

          Future<String> cancel =
              pool.submit(() -> outcome(go, () -> trips.cancel(tripId, rider.asRider(), "Race")));
          Future<String> arrive =
              pool.submit(() -> outcome(go, () -> trips.arrive(tripId, driver.asDriver())));
          go.countDown();

          List<String> outcomes =
              List.of(cancel.get(30, TimeUnit.SECONDS), arrive.get(30, TimeUnit.SECONDS));
          assertThat(outcomes)
              .as("round %d", round)
              .allMatch(
                  outcome ->
                      outcome.equals("ok")
                          || outcome.equals("TRIP_INVALID_TRANSITION")
                          || outcome.equals("TRIP_CONCURRENT_UPDATE"));
          assertValidAuditChain(tripId, round);
        }
      }
    }

    private String outcome(CountDownLatch go, Runnable action) throws InterruptedException {
      go.await();
      try {
        action.run();
        return "ok";
      } catch (BusinessException e) {
        return e.code();
      }
    }

    /** From DRIVER_ASSIGNED, each row must start where the last one ended, by an allowed move. */
    private void assertValidAuditChain(UUID tripId, int round) {
      Map<String, String> next = new HashMap<>();
      jdbc.query(
          "SELECT from_status, to_status FROM trip_events WHERE trip_id = ?",
          row -> {
            assertThat(next.put(row.getString("from_status"), row.getString("to_status")))
                .as("round %d: two rows leave the same state", round)
                .isNull();
          },
          tripId);

      String state = TripStatus.DRIVER_ASSIGNED.name();
      int links = 0;
      while (next.containsKey(state)) {
        String from = state;
        String to = next.get(state);
        assertThat(TripStateMachine.TRANSITIONS)
            .as("round %d: %s -> %s is not in the table", round, from, to)
            .anyMatch(t -> t.from().name().equals(from) && t.to().name().equals(to));
        state = to;
        links++;
      }
      assertThat(links).as("round %d: rows outside the chain", round).isEqualTo(next.size());
      assertThat(statusOf(tripId).name()).as("round %d: final state", round).isEqualTo(state);
    }
  }
}
