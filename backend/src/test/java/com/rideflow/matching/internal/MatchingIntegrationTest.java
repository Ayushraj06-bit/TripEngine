package com.rideflow.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.driver.DriverStatus;
import com.rideflow.location.DriverWentStale;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripStatus;
import com.rideflow.trip.internal.Caller;
import com.rideflow.trip.internal.TripService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The matching engine end to end: real Redis, real PostgreSQL, the real map, a moved clock. */
class MatchingIntegrationTest extends MatchingIntegrationTestBase {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);

  @Autowired TripService trips;
  @Autowired ApplicationEventPublisher events;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired MeterRegistry meters;

  private ResultActions postAs(User driver, String path) throws Exception {
    return mockMvc.perform(post(path).header("Authorization", "Bearer " + driver.token()));
  }

  private ResultActions getAs(User driver, String path) throws Exception {
    return mockMvc.perform(get(path).header("Authorization", "Bearer " + driver.token()));
  }

  private double count(String meter, String... tags) {
    var counter = meters.find(meter).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  /** Runs {@code action} on {@code threads} threads released at the same moment. */
  private static void concurrently(int threads, Runnable action) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        runs.add(
            pool.submit(
                () -> {
                  go.await();
                  action.run();
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> run : runs) {
        run.get(30, TimeUnit.SECONDS);
      }
    }
  }

  private long tripsIn(TripStatus status) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trips WHERE status = ?", Long.class, status.name());
  }

  @Nested
  class Offering {

    @Test
    void offersTheNearestDriverAndAssignsTheTripWhenTheyAccept() {
      UUID rider = rider();
      User near = driverAt(north(500));
      User far = driverAt(north(1_500));
      long assignedBefore = meters.timer("time.to.assign").count();

      UUID tripId = book(rider);
      OfferView offer = awaitOffer(near.id());

      assertThat(NOTIFICATIONS.offersTo(far.id())).isEmpty();
      // About 110 m of precision until the driver commits.
      assertThat(offer.pickup()).isEqualTo(new OfferView.Point(12.975, 77.606));
      assertThat(offer.estimatedFarePaise()).isPositive();
      assertThat(offer.expiresAt()).isEqualTo(START.plusSeconds(12));
      assertThat(counter(near.id(), "offers_received")).isOne();

      matcher.accept(offer.offerId(), near.id());

      assertThat(statusOf(tripId)).isEqualTo(TripStatus.DRIVER_ASSIGNED);
      assertThat(counter(near.id(), "offers_accepted")).isOne();
      assertThat(meters.timer("time.to.assign").count()).isEqualTo(assignedBefore + 1);
      await().atMost(EVENTUALLY).until(() -> driverStatus(near.id()) == DriverStatus.ON_TRIP);
      await()
          .atMost(EVENTUALLY)
          .until(
              () ->
                  finder.findAvailableDrivers(PICKUP, VehicleType.CAR, 2.0, 20).stream()
                      .noneMatch(driver -> driver.driverId().equals(near.id())));
    }

    @Test
    void movesToTheNextDriverWhenAnOfferExpiresAndReleasesTheFirst() {
      User near = driverAt(north(500));
      User far = driverAt(north(1_500));
      UUID tripId = requestedTrip(rider());

      matcher.start(tripId, null);
      OfferView first = awaitOffer(near.id());
      assertThat(locked(near.id())).isTrue();
      double expiredBefore = count("offers.result", "result", "expired");

      advanceAndPoll(Duration.ofSeconds(12));

      assertThat(count("offers.result", "result", "expired")).isEqualTo(expiredBefore + 1);
      assertThat(offerStatus(first.offerId())).isEqualTo("EXPIRED");
      assertThat(locked(near.id())).isFalse();
      assertThat(NOTIFICATIONS.offersTo(far.id())).hasSize(1);
    }

    @Test
    void movesToTheNextDriverAtOnceWhenOneRejects() {
      User near = driverAt(north(500));
      User far = driverAt(north(1_500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      double rejectedBefore = count("offers.result", "result", "rejected");

      matcher.reject(awaitOffer(near.id()).offerId(), near.id());

      assertThat(count("offers.result", "result", "rejected")).isEqualTo(rejectedBefore + 1);
      // No clock movement: the next offer does not wait for anything.
      assertThat(locked(near.id())).isFalse();
      assertThat(NOTIFICATIONS.offersTo(far.id())).hasSize(1);
    }

    @Test
    void neverOffersADriverASecondTripWhileTheyHoldAnOffer() {
      User only = driverAt(north(500));
      UUID first = requestedTrip(rider());
      UUID second = requestedTrip(rider());

      matcher.start(first, null);
      matcher.start(second, null);

      assertThat(NOTIFICATIONS.offersTo(only.id())).hasSize(1);
      assertThat(store.retryAt(second)).isPresent();
    }

    /** Duplicate events are normal. Two drivers, so a second start would have someone to offer. */
    @Test
    void startsOnceHoweverManyTimesTheTripIsAnnounced() throws Exception {
      driverAt(north(500));
      driverAt(north(1_000));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      assertThat(NOTIFICATIONS.offers()).hasSize(1);

      // Late duplicates, once the first offer is already out, and all at once.
      concurrently(10, () -> matcher.start(tripId, null));

      assertThat(NOTIFICATIONS.offers()).hasSize(1);
      assertThat(redis.opsForZSet().size(OfferStore.OFFERS_EXPIRY)).isOne();
    }

    /**
     * Steps that race on one trip - a search falling due while the sweep restarts it, say - still
     * send one offer between them: each may only replace the offer it saw.
     */
    @Test
    void sendsOneOfferWhenStepsForTheSameTripRace() throws Exception {
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      for (int i = 0; i < 3; i++) {
        driverAt(north(300 + 300 * i));
      }

      concurrently(10, () -> matcher.advance(tripId));

      assertThat(NOTIFICATIONS.offers()).hasSize(1);
      assertThat(redis.opsForZSet().size(OfferStore.OFFERS_EXPIRY)).isOne();
    }

    @Test
    void letsADriverWhoMissedThePushReadTheirOffer() throws Exception {
      User offered = driverAt(north(500));
      User other = driverAt(north(3_000));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      UUID offerId = awaitOffer(offered.id()).offerId();

      getAs(offered, "/api/v1/offers/current")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.offerId").value(offerId.toString()))
          .andExpect(jsonPath("$.type").value("OFFER"));
      getAs(other, "/api/v1/offers/current").andExpect(status().isNoContent());
    }
  }

  @Nested
  class Searching {

    @Test
    void widensTheSearchAndThenKeepsLookingUntilTheDeadline() {
      User inner = driverAt(north(1_500));
      User outer = driverAt(north(3_500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);

      matcher.reject(awaitOffer(inner.id()).offerId(), inner.id());
      // Nobody else within 2 km: the search widened to 4 km.
      assertThat(redis.opsForHash().get("match:" + tripId, "radiusKm")).isEqualTo("4.0");
      matcher.reject(awaitOffer(outer.id()).offerId(), outer.id());

      // Nobody left. It does not fail: it looks again every 5 s.
      assertThat(store.retryAt(tripId)).contains(START.plusSeconds(5));
      for (int seconds = 5; seconds < 60; seconds += 5) {
        advanceAndPoll(Duration.ofSeconds(5));
        assertThat(statusOf(tripId)).as("after %d s", seconds).isEqualTo(TripStatus.REQUESTED);
      }

      advanceAndPoll(Duration.ofSeconds(5));

      assertThat(statusOf(tripId)).isEqualTo(TripStatus.NO_DRIVER_FOUND);
      assertThat(NOTIFICATIONS.offers()).hasSize(2);
      assertThat(redis.hasKey("match:" + tripId)).isFalse();
    }

    @Test
    void findsADriverWhoComesOnlineWhileItIsStillLooking() {
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      assertThat(store.retryAt(tripId)).isPresent();

      User arriving = driverAt(north(300));
      advanceAndPoll(Duration.ofSeconds(5));

      assertThat(NOTIFICATIONS.offersTo(arriving.id())).hasSize(1);
    }
  }

  @Nested
  class AnsweringOverHttp {

    @Test
    void answersTheSameWhenAnOfferIsAcceptedTwice() throws Exception {
      User driver = driverAt(north(500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      UUID offerId = awaitOffer(driver.id()).offerId();

      String first =
          postAs(driver, "/api/v1/offers/" + offerId + "/accept")
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.tripId").value(tripId.toString()))
              .andReturn()
              .getResponse()
              .getContentAsString();
      String second =
          postAs(driver, "/api/v1/offers/" + offerId + "/accept")
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      JSONAssert.assertEquals(first, second, true);
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.DRIVER_ASSIGNED);
      assertThat(counter(driver.id(), "offers_accepted")).isOne();
    }

    @Test
    void refusesAnAcceptanceAfterTheOfferExpired() throws Exception {
      User driver = driverAt(north(500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      UUID offerId = awaitOffer(driver.id()).offerId();

      // Expired by the clock, though the poller has not run yet.
      CLOCK.advance(Duration.ofSeconds(12));

      postAs(driver, "/api/v1/offers/" + offerId + "/accept")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.REQUESTED);
    }

    @Test
    void hidesAnOfferFromEveryDriverButTheOneItWasMadeTo() throws Exception {
      User offered = driverAt(north(500));
      User other = driverAt(north(3_000));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      UUID offerId = awaitOffer(offered.id()).offerId();

      postAs(other, "/api/v1/offers/" + offerId + "/accept").andExpect(status().isNotFound());
      postAs(other, "/api/v1/offers/" + offerId + "/reject").andExpect(status().isNotFound());

      postAs(offered, "/api/v1/offers/" + offerId + "/accept").andExpect(status().isOk());
    }

    @Test
    void takesARepeatedRejectionCalmly() throws Exception {
      User driver = driverAt(north(500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      UUID offerId = awaitOffer(driver.id()).offerId();

      postAs(driver, "/api/v1/offers/" + offerId + "/reject")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("REJECTED"));
      postAs(driver, "/api/v1/offers/" + offerId + "/reject").andExpect(status().isOk());
    }
  }

  @Nested
  class WhenThingsChange {

    @Test
    void withdrawsTheOfferWhenTheRiderCancels() {
      User driver = driverAt(north(500));
      UUID rider = rider();
      UUID tripId = book(rider);
      OfferView offer = awaitOffer(driver.id());
      double withdrawnBefore = count("offers.result", "result", "withdrawn");

      trips.cancel(tripId, new Caller(rider, TripActor.RIDER), "Plans changed");

      await().atMost(EVENTUALLY).until(() -> !NOTIFICATIONS.withdrawalsTo(driver.id()).isEmpty());
      assertThat(count("offers.result", "result", "withdrawn")).isEqualTo(withdrawnBefore + 1);
      assertThat(offerStatus(offer.offerId())).isEqualTo("WITHDRAWN");
      assertThat(locked(driver.id())).isFalse();
      assertThat(redis.hasKey("match:" + tripId)).isFalse();
    }

    /** The rider cancelled a moment before the driver accepted - here, behind matching's back. */
    @Test
    void releasesTheDriverWhenTheTripCanNoLongerBeAssigned() {
      UUID driver = driverRow();
      UUID tripId = requestedTrip(rider());
      UUID offerId = offerDirectly(tripId, driver);
      jdbc.update(
          "UPDATE trips SET status = 'CANCELLED', cancelled_by = 'RIDER', cancelled_at = ?"
              + " WHERE id = ?",
          utc(CLOCK.instant()),
          tripId);

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> matcher.accept(offerId, driver))
          .satisfies(e -> assertThat(e.code()).isEqualTo("RIDE_NO_LONGER_AVAILABLE"));
      assertThat(locked(driver)).isFalse();
      assertThat(offerStatus(offerId)).isEqualTo("WITHDRAWN");
      assertThat(redis.hasKey("match:" + tripId)).isFalse();
    }

    /**
     * Against the locks, the driver already has a trip: the database refuses, matching moves on.
     */
    @Test
    void movesOnWhenTheAcceptingDriverAlreadyHasATrip() {
      UUID busyDriver = driverRow();
      UUID earlier = requestedTrip(rider());
      jdbc.update(
          "UPDATE trips SET status = 'DRIVER_ASSIGNED', driver_id = ?, assigned_at = ? WHERE id = ?",
          busyDriver,
          utc(CLOCK.instant()),
          earlier);
      UUID tripId = requestedTrip(rider());
      UUID offerId = offerDirectly(tripId, busyDriver);
      User next = driverAt(north(500));

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> matcher.accept(offerId, busyDriver))
          .satisfies(e -> assertThat(e.code()).isEqualTo("RIDE_NO_LONGER_AVAILABLE"));
      assertThat(locked(busyDriver)).isFalse();
      assertThat(NOTIFICATIONS.offersTo(next.id())).hasSize(1);
    }

    /** The real event bus this time, not a direct call: the second delivery changes nothing. */
    @Test
    void ignoresATripRequestedEventDeliveredTwice() {
      driverAt(north(500));
      driverAt(north(1_000));
      UUID rider = rider();
      UUID tripId = book(rider);
      await().atMost(EVENTUALLY).until(() -> NOTIFICATIONS.offers().size() == 1);
      UUID eventId = UUID.randomUUID();

      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status ->
                  events.publishEvent(
                      new TripRequested(
                          eventId,
                          CLOCK.instant(),
                          tripId,
                          rider,
                          VehicleType.CAR,
                          PICKUP.latitude(),
                          PICKUP.longitude(),
                          12.9784,
                          77.6408,
                          9900,
                          java.math.BigDecimal.ONE,
                          PaymentMethod.WALLET)));

      await()
          .atMost(EVENTUALLY)
          .until(
              () ->
                  jdbc.queryForObject(
                          "SELECT count(*) FROM event_publication"
                              + " WHERE serialized_event LIKE ? AND completion_date IS NULL",
                          Long.class,
                          "%" + eventId + "%")
                      == 0);
      assertThat(NOTIFICATIONS.offers()).hasSize(1);
    }

    @Test
    void treatsADriverWhoGoesStaleAsHavingRejectedAtOnce() {
      User near = driverAt(north(500));
      User far = driverAt(north(1_500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      OfferView offer = awaitOffer(near.id());

      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status ->
                  events.publishEvent(
                      new DriverWentStale(near.id(), CLOCK.instant(), CLOCK.instant())));

      // Without waiting out the 12 s timeout.
      await().atMost(EVENTUALLY).until(() -> !NOTIFICATIONS.offersTo(far.id()).isEmpty());
      assertThat(offerStatus(offer.offerId())).isEqualTo("REJECTED");
    }

    @Test
    void treatsADriverWhoGoesOfflineAsHavingRejectedAtOnce() {
      User near = driverAt(north(500));
      User far = driverAt(north(1_500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      OfferView offer = awaitOffer(near.id());

      driverService.goOffline(near.id());

      await().atMost(EVENTUALLY).until(() -> !NOTIFICATIONS.offersTo(far.id()).isEmpty());
      assertThat(offerStatus(offer.offerId())).isEqualTo("REJECTED");
    }

    @Test
    void givesTheTripToSomeoneElseWhenItsDriverBacksOut() {
      User first = driverAt(north(500));
      User second = driverAt(north(1_500));
      UUID tripId = book(rider());
      matcher.accept(awaitOffer(first.id()).offerId(), first.id());

      CLOCK.advance(Duration.ofSeconds(30));
      trips.cancel(tripId, new Caller(first.id(), TripActor.DRIVER), "Car trouble");

      awaitOffer(second.id());
      // Never back to the driver who just walked away from it.
      assertThat(NOTIFICATIONS.offersTo(first.id())).hasSize(1);
      // A full search from the moment it came back, not what was left of the first.
      assertThat(redis.opsForHash().get("match:" + tripId, "deadline"))
          .isEqualTo(Long.toString(START.plusSeconds(90).toEpochMilli()));
      // Section 6: backing out counts against them.
      await().atMost(EVENTUALLY).until(() -> counter(first.id(), "offers_accepted") == 0);
    }
  }

  @Nested
  class Recovery {

    /** What a Redis failover leaves: no matching state, and a lock that guards nothing. */
    private void loseMatchingState() {
      Set<String> keys = new HashSet<>(redis.keys("match:*"));
      keys.addAll(redis.keys("offer:*"));
      keys.add(OfferStore.OFFERS_EXPIRY);
      redis.delete(keys);
    }

    @Test
    void restartsMatchingThatRedisLostAndStillAssignsTheTrip() {
      User driver = driverAt(north(500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      awaitOffer(driver.id());
      double restartsBefore = count("match.restarts");

      loseMatchingState();
      CLOCK.advance(Duration.ofSeconds(11));
      sweeper.sweep();

      assertThat(NOTIFICATIONS.offersTo(driver.id())).hasSize(2);
      matcher.accept(NOTIFICATIONS.offersTo(driver.id()).getLast().offerId(), driver.id());
      assertThat(statusOf(tripId)).isEqualTo(TripStatus.DRIVER_ASSIGNED);
      assertThat(count("match.restarts")).isEqualTo(restartsBefore + 1);
    }

    @Test
    void leavesMatchingThatIsStillInHandAlone() {
      User driver = driverAt(north(500));
      UUID tripId = requestedTrip(rider());
      matcher.start(tripId, null);
      awaitOffer(driver.id());
      double restartsBefore = count("match.restarts");

      // The offer is 11 s old and expires at 12: in hand, not lost.
      CLOCK.advance(Duration.ofSeconds(11));
      sweeper.sweep();

      assertThat(count("match.restarts")).isEqualTo(restartsBefore);
      assertThat(NOTIFICATIONS.offersTo(driver.id())).hasSize(1);
    }

    @Test
    void leavesTripsYoungerThanTheGracePeriodAlone() {
      UUID tripId = requestedTrip(rider());
      double restartsBefore = count("match.restarts");

      // Its first steps may simply still be running.
      CLOCK.advance(Duration.ofSeconds(5));
      sweeper.sweep();

      assertThat(redis.hasKey("match:" + tripId)).isFalse();
      assertThat(count("match.restarts")).isEqualTo(restartsBefore);
    }

    @Test
    void givesUpOnATripWhoseDeadlinePassedWhileNobodyWasMatchingIt() {
      UUID tripId = requestedTrip(rider());

      CLOCK.advance(Duration.ofSeconds(61));
      sweeper.sweep();

      assertThat(statusOf(tripId)).isEqualTo(TripStatus.NO_DRIVER_FOUND);
    }
  }

  /** The building blocks the races depend on, checked directly. */
  @Nested
  class Store {

    @Test
    void releasesALockOnlyForTheOfferThatTookIt() {
      UUID driver = driverRow();
      UUID mine = UUID.randomUUID();
      assertThat(store.lock(driver, mine)).isTrue();

      store.unlock(driver, UUID.randomUUID());
      assertThat(locked(driver)).isTrue();

      store.unlock(driver, mine);
      assertThat(locked(driver)).isFalse();
    }

    @Test
    void endsAMatchSoThatNoOfferCanFollowAndReportsTheLastOne() {
      UUID driver = driverRow();
      UUID tripId = requestedTrip(rider());
      UUID offerId = offerDirectly(tripId, driver);
      OfferStore.Match ended = store.match(tripId).orElseThrow();

      assertThat(store.finish(tripId)).contains(offerId);

      // A step that read the match before it ended can no longer add to it.
      UUID late = UUID.randomUUID();
      assertThat(store.createOffer(ended, late, driverRow(), 2.0, CLOCK.instant().plusSeconds(12)))
          .isEqualTo(OfferStore.Creation.NO_MATCH);
      assertThat(store.finish(tripId)).isEmpty();
    }
  }

  @Nested
  class Races {

    /** 200 rounds of a driver accepting at the instant the offer runs out. */
    @Test
    void acceptAndTimeoutNeverBothWin() throws Exception {
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (int round = 0; round < 200; round++) {
          redis.getConnectionFactory().getConnection().serverCommands().flushDb();
          UUID driver = driverRow();
          UUID tripId = requestedTrip(rider());
          UUID offerId = offerDirectly(tripId, driver);
          Instant expiresAt = CLOCK.instant().plusSeconds(12);
          CLOCK.set(expiresAt.minusMillis(1));
          CountDownLatch go = new CountDownLatch(1);

          Future<String> accept =
              pool.submit(
                  () -> {
                    go.await();
                    try {
                      matcher.accept(offerId, driver);
                      return "accepted";
                    } catch (BusinessException refused) {
                      return refused.code();
                    }
                  });
          Future<?> timeout =
              pool.submit(
                  () -> {
                    go.await();
                    CLOCK.set(expiresAt);
                    poller.poll();
                    return null;
                  });
          go.countDown();
          String accepted = accept.get(30, TimeUnit.SECONDS);
          timeout.get(30, TimeUnit.SECONDS);

          if (accepted.equals("accepted")) {
            assertThat(offerStatus(offerId)).as("round %d", round).isEqualTo("ACCEPTED");
            assertThat(statusOf(tripId))
                .as("round %d", round)
                .isEqualTo(TripStatus.DRIVER_ASSIGNED);
          } else {
            assertThat(accepted).as("round %d", round).isEqualTo("OFFER_EXPIRED");
            assertThat(offerStatus(offerId)).as("round %d", round).isEqualTo("EXPIRED");
            assertThat(statusOf(tripId)).as("round %d", round).isEqualTo(TripStatus.REQUESTED);
          }
        }
      }
    }

    /**
     * 100 rounds of a driver accepting while the rider cancels. Either the trip was assigned and
     * then cancelled, or the driver hears it is gone - and once the events settle, the driver is ON
     * TRIP exactly when they have a trip.
     */
    @Test
    void acceptRacingTheRidersCancelNeverStrandsTheDriver() throws Exception {
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (int round = 0; round < 100; round++) {
          redis.getConnectionFactory().getConnection().serverCommands().flushDb();
          UUID rider = rider();
          UUID driver = driverRow();
          UUID tripId = requestedTrip(rider);
          UUID offerId = offerDirectly(tripId, driver);
          CountDownLatch go = new CountDownLatch(1);

          Future<String> accept =
              pool.submit(
                  () -> {
                    go.await();
                    try {
                      matcher.accept(offerId, driver);
                      return "accepted";
                    } catch (BusinessException refused) {
                      return refused.code();
                    }
                  });
          Future<String> cancel =
              pool.submit(
                  () -> {
                    go.await();
                    try {
                      trips.cancel(tripId, new Caller(rider, TripActor.RIDER), "Race");
                      return "cancelled";
                    } catch (BusinessException refused) {
                      return refused.code();
                    }
                  });
          go.countDown();

          assertThat(accept.get(30, TimeUnit.SECONDS))
              .as("round %d", round)
              .isIn("accepted", "RIDE_NO_LONGER_AVAILABLE");
          assertThat(cancel.get(30, TimeUnit.SECONDS))
              .as("round %d", round)
              .isIn("cancelled", "TRIP_CONCURRENT_UPDATE");

          TripStatus trip = statusOf(tripId);
          assertThat(trip)
              .as("round %d", round)
              .isIn(TripStatus.CANCELLED, TripStatus.DRIVER_ASSIGNED);
          DriverStatus expected =
              trip == TripStatus.DRIVER_ASSIGNED ? DriverStatus.ON_TRIP : DriverStatus.AVAILABLE;
          final int r = round;
          await()
              .atMost(EVENTUALLY)
              .untilAsserted(
                  () -> assertThat(driverStatus(driver)).as("round %d", r).isEqualTo(expected));
        }
      }
    }
  }

  @Nested
  class AtScale {

    /**
     * The test the engine exists to pass: a hundred riders book at once, ten drivers accept
     * everything they are offered. Exactly ten trips get a driver, no driver ever holds two, and
     * the other ninety search until their deadline and give up.
     */
    @Test
    void aHundredRidersAndTenDriversMakeExactlyTenTrips() throws Exception {
      List<User> drivers = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        drivers.add(driverAt(north(100 + 150 * i)));
      }
      List<UUID> riders = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        riders.add(rider());
      }
      double sentBefore = count("offers.sent");
      double acceptedBefore = count("offers.result", "result", "accepted");
      double gaveUpBefore = count("no.driver.found");

      try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
        List<Future<UUID>> bookings = new ArrayList<>();
        for (UUID rider : riders) {
          bookings.add(pool.submit(() -> book(rider)));
        }
        for (Future<UUID> booking : bookings) {
          booking.get(60, TimeUnit.SECONDS);
        }
      }

      // The drivers' apps accept whatever arrives, while matching is still under way.
      Set<UUID> answered = new HashSet<>();
      await()
          .atMost(Duration.ofSeconds(60))
          .pollInSameThread()
          .until(
              () -> {
                for (var sent : NOTIFICATIONS.offers()) {
                  OfferView offer = (OfferView) sent.payload();
                  if (answered.add(offer.offerId())) {
                    matcher.accept(offer.offerId(), sent.userId());
                  }
                }
                return tripsIn(TripStatus.DRIVER_ASSIGNED) == 10
                    && redis.opsForZSet().size(OfferStore.RETRIES) == 90;
              });

      // Nobody else comes online: every other trip keeps looking until its deadline.
      for (int i = 0; i < 12; i++) {
        advanceAndPoll(Duration.ofSeconds(5));
      }

      assertThat(tripsIn(TripStatus.DRIVER_ASSIGNED)).isEqualTo(10);
      assertThat(tripsIn(TripStatus.NO_DRIVER_FOUND)).isEqualTo(90);
      assertThat(
              jdbc.queryForList(
                  "SELECT driver_id FROM trips"
                      + " WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS')"
                      + " GROUP BY driver_id HAVING count(*) > 1",
                  UUID.class))
          .as("drivers holding more than one active trip")
          .isEmpty();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(DISTINCT driver_id) FROM trips WHERE status = 'DRIVER_ASSIGNED'",
                  Long.class))
          .isEqualTo(10);
      assertThat(count("offers.sent")).isEqualTo(sentBefore + 10);
      assertThat(count("offers.result", "result", "accepted")).isEqualTo(acceptedBefore + 10);
      assertThat(count("no.driver.found")).isEqualTo(gaveUpBefore + 90);
    }
  }
}
