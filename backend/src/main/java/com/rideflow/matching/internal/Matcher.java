package com.rideflow.matching.internal;

import com.rideflow.driver.CandidateFacts;
import com.rideflow.driver.DriverCandidates;
import com.rideflow.location.AvailableDriver;
import com.rideflow.location.AvailableDriverFinder;
import com.rideflow.matching.internal.CandidateRanker.Candidate;
import com.rideflow.matching.internal.OfferStore.Match;
import com.rideflow.matching.internal.OfferStore.OfferStatus;
import com.rideflow.notification.Notifications;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.TripAssignment;
import com.rideflow.trip.WaitingTrip;
import com.rideflow.trip.WaitingTrips;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Finds a driver for a trip, one offer at a time, without any thread ever waiting for an answer.
 *
 * <p>Every step reacts to something - a trip requested, an offer accepted, rejected or expired, a
 * search falling due - does a little work against Redis, and returns. The waiting happens in Redis,
 * as an expiry time in a sorted set that a poller watches. So a thousand trips being matched cost a
 * thousand small hashes, not a thousand blocked threads, and any instance can pick up any step:
 * nothing lives in one instance's memory.
 *
 * <p>Redis decides who is offered what, atomically. PostgreSQL decides who gets the trip: an
 * accepted offer still goes through {@code TripAssignment.assignDriver}, where the trip's version
 * and the one-active-trip-per-driver index are the final guard.
 */
@Service
public class Matcher {

  static final String OFFERS_DESTINATION = "/queue/offers";

  private static final Logger log = LoggerFactory.getLogger(Matcher.class);

  private final OfferStore store;
  private final AvailableDriverFinder finder;
  private final DriverCandidates drivers;
  private final WaitingTrips waitingTrips;
  private final TripAssignment trips;
  private final Notifications notifications;
  private final MatchingMetrics metrics;
  private final IdGenerator ids;
  private final Clock clock;
  private final RideFlowProperties.Matching rules;
  private final CandidateRanker ranker;

  Matcher(
      OfferStore store,
      AvailableDriverFinder finder,
      DriverCandidates drivers,
      WaitingTrips waitingTrips,
      TripAssignment trips,
      Notifications notifications,
      MatchingMetrics metrics,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties) {
    this.store = store;
    this.finder = finder;
    this.drivers = drivers;
    this.waitingTrips = waitingTrips;
    this.trips = trips;
    this.notifications = notifications;
    this.metrics = metrics;
    this.ids = ids;
    this.clock = clock;
    this.rules = properties.matching();
    this.ranker = new CandidateRanker(rules.fairnessBucketMeters(), rules.minDriverRating());
  }

  /** The answer to an accepted offer: the trip the driver now has. */
  public record AcceptedOffer(UUID offerId, UUID tripId, String status) {}

  public record RejectedOffer(UUID offerId, String status) {}

  /** Pushed to a driver whose offer the rider withdrew by cancelling. */
  record OfferWithdrawn(String type, UUID offerId) {}

  // --- Starting and stopping -------------------------------------------------------------------

  /**
   * Starts matching a trip, unless it is already being matched or no longer needs a driver.
   *
   * <p>The trip's details come from the database rather than the event, so a duplicate event
   * arriving after the trip was assigned finds it no longer waiting and does nothing.
   *
   * @param excludedDriver never offered this trip: the driver who just backed out of it, or null
   */
  public void start(UUID tripId, UUID excludedDriver) {
    waitingTrips.waiting(tripId).ifPresent(trip -> begin(trip, excludedDriver));
  }

  private void begin(WaitingTrip trip, UUID excludedDriver) {
    Instant deadline = trip.waitingSince().plusSeconds(rules.deadlineSeconds());
    if (store.startMatch(trip, deadline, excludedDriver)) {
      advance(trip.tripId());
    }
  }

  /** The rider cancelled while a driver was being found: withdraw the offer, stop looking. */
  public void stop(UUID tripId) {
    store
        .match(tripId)
        .map(Match::currentOffer)
        .ifPresent(
            offerId -> {
              OfferStore.Closing closing =
                  store.close(offerId, OfferStatus.WITHDRAWN, OfferStatus.PENDING, null);
              if (closing.closed()) {
                store.unlock(closing.driverId(), offerId);
                metrics.offerResult("withdrawn");
                bestEffort(
                    "tell driver " + closing.driverId() + " the offer was withdrawn",
                    () ->
                        notifications.sendToUser(
                            closing.driverId(),
                            OFFERS_DESTINATION,
                            new OfferWithdrawn("OFFER_WITHDRAWN", offerId)));
              }
            });
    store.finish(tripId);
  }

  // --- Searching -------------------------------------------------------------------------------

  private enum Attempt {
    SENT,
    NOBODY,
    /** Someone else moved the trip on, or matching ended under us. */
    ENDED
  }

  /**
   * Offers the trip to the next candidate. If there is none, searches again after {@code
   * research-interval-seconds} - drivers come online all the time - until the deadline, then gives
   * up.
   */
  void advance(UUID tripId) {
    Optional<Match> found = store.match(tripId);
    if (found.isEmpty()) {
      return;
    }
    Match match = found.get();
    if (match.currentOffer() != null
        && store.offer(match.currentOffer()).map(o -> o.status().holdsDriver()).orElse(false)) {
      // An offer is out, and its outcome will move the trip along.
      return;
    }
    Instant now = clock.instant();
    if (!now.isBefore(match.deadline())) {
      giveUp(tripId);
      return;
    }
    if (offerToNextCandidate(match, now) == Attempt.NOBODY) {
      Instant next = now.plusSeconds(rules.researchIntervalSeconds());
      store.scheduleRetry(tripId, next.isBefore(match.deadline()) ? next : match.deadline());
    }
  }

  private Attempt offerToNextCandidate(Match match, Instant now) {
    Set<UUID> tried = store.tried(match.tripId());
    for (double radiusKm : rules.radiusStepsKm()) {
      List<AvailableDriver> fresh =
          finder
              .findAvailableDrivers(
                  match.pickup(), match.vehicleType(), radiusKm, rules.candidatesPerSearch())
              .stream()
              .filter(driver -> !tried.contains(driver.driverId()))
              .toList();
      if (fresh.isEmpty()) {
        continue;
      }
      Set<UUID> busy = store.busy(fresh.stream().map(AvailableDriver::driverId).toList());
      List<AvailableDriver> free =
          fresh.stream().filter(driver -> !busy.contains(driver.driverId())).toList();
      Map<UUID, CandidateFacts> facts =
          drivers.factsFor(free.stream().map(AvailableDriver::driverId).toList());

      List<Candidate> candidates = new ArrayList<>();
      for (AvailableDriver driver : free) {
        CandidateFacts known = facts.get(driver.driverId());
        if (known != null) {
          candidates.add(new Candidate(driver.distanceMeters(), known));
        }
      }
      for (Candidate candidate : ranker.rank(candidates)) {
        Attempt attempt = offer(match, candidate.driverId(), radiusKm, now);
        if (attempt != Attempt.NOBODY) {
          return attempt;
        }
      }
    }
    return Attempt.NOBODY;
  }

  private Attempt offer(Match match, UUID driverId, double radiusKm, Instant now) {
    UUID offerId = ids.newId();
    if (!store.lock(driverId, offerId)) {
      // Another trip's offer reached this driver first.
      return Attempt.NOBODY;
    }
    Instant expiresAt = now.plusSeconds(rules.offerTimeoutSeconds());
    if (store.createOffer(match, offerId, driverId, radiusKm, expiresAt)
        != OfferStore.Creation.CREATED) {
      store.unlock(driverId, offerId);
      return Attempt.ENDED;
    }
    metrics.offerSent();
    // The offer stands whatever happens below: unseen, it simply expires.
    bestEffort(
        "push an offer to driver " + driverId,
        () ->
            notifications.sendToUser(
                driverId,
                OFFERS_DESTINATION,
                OfferView.of(offerId, match, expiresAt, rules.offerPickupRoundingDecimals())));
    bestEffort("count an offer for driver " + driverId, () -> drivers.recordOfferSent(driverId));
    return Attempt.SENT;
  }

  private void giveUp(UUID tripId) {
    try {
      trips.markNoDriverFound(tripId);
      metrics.noDriverFound();
    } catch (BusinessException moved) {
      // Cancelled or assigned in the meantime: nothing is left to give up on.
      log.debug("Trip {} was no longer waiting at its deadline: {}", tripId, moved.code());
    }
    store.finish(tripId);
  }

  // --- What happens to an offer ----------------------------------------------------------------

  /**
   * The driver takes the trip. Redis settles the race with expiry, rejection and withdrawal; {@code
   * assignDriver} then settles it with everything else, in the database.
   *
   * <p>Accepting twice answers the same: the second finds the offer already ACCEPTED, and assigning
   * the same driver again changes nothing.
   *
   * @throws BusinessException 404 for a missing offer or another driver's; 409 {@code
   *     OFFER_EXPIRED}; 409 {@code RIDE_NO_LONGER_AVAILABLE} if it was withdrawn, rejected, or the
   *     trip can no longer be assigned
   */
  public AcceptedOffer accept(UUID offerId, UUID driverId) {
    OfferStore.Acceptance acceptance = store.accept(offerId, driverId, clock.instant());
    switch (acceptance.outcome()) {
      case "accepted" -> {
        // Settled in Redis; now the database.
      }
      case "expired" -> throw BusinessException.conflict("OFFER_EXPIRED", "This offer has expired");
      case "rejected", "withdrawn" -> throw rideNoLongerAvailable(null);
      // Missing, or another driver's: the same answer, so neither reveals the other.
      default -> throw BusinessException.notFound("Offer not found");
    }

    UUID tripId = acceptance.tripId();
    try {
      trips.assignDriver(tripId, driverId);
    } catch (BusinessException refused) {
      // The rider cancelled a moment earlier, or - against the locks - the driver is on
      // another trip. Release the driver either way.
      store.close(offerId, OfferStatus.WITHDRAWN, OfferStatus.ACCEPTED, null);
      store.unlock(driverId, offerId);
      if (acceptance.first()) {
        metrics.offerResult("withdrawn");
      }
      if ("DRIVER_HAS_ACTIVE_TRIP".equals(refused.code())) {
        advanceQuietly(tripId);
      } else {
        store.finish(tripId);
      }
      throw rideNoLongerAvailable(refused);
    }

    if (acceptance.first()) {
      store
          .match(tripId)
          .ifPresent(
              match -> metrics.timeToAssign(Duration.between(match.startedAt(), clock.instant())));
      metrics.offerResult("accepted");
      bestEffort(
          "count an acceptance for driver " + driverId,
          () -> drivers.recordOfferAccepted(driverId));
      // The driver's lock stays until it expires: it keeps other trips off them until the
      // location module has taken them off the map, which follows a moment later.
      store.finish(tripId);
    }
    return new AcceptedOffer(offerId, tripId, "ACCEPTED");
  }

  /**
   * The driver turns the trip down; the next candidate is offered it at once. Rejecting twice is
   * not an error.
   */
  public RejectedOffer reject(UUID offerId, UUID driverId) {
    OfferStore.Closing closing =
        store.close(offerId, OfferStatus.REJECTED, OfferStatus.PENDING, driverId);
    switch (closing.outcome()) {
      case "closed" -> {
        store.unlock(driverId, offerId);
        metrics.offerResult("rejected");
        advanceQuietly(closing.tripId());
      }
      case "not_open" -> {
        if (closing.status() != OfferStatus.REJECTED) {
          throw new BusinessException(
              "OFFER_NOT_PENDING",
              HttpStatus.CONFLICT,
              "This offer is no longer open",
              Map.of("offerStatus", closing.status()));
        }
      }
      default -> throw BusinessException.notFound("Offer not found");
    }
    return new RejectedOffer(offerId, "REJECTED");
  }

  /** The poller found the offer past its expiry. Unless something else ended it first. */
  void expire(UUID offerId) {
    OfferStore.Closing closing =
        store.close(offerId, OfferStatus.EXPIRED, OfferStatus.PENDING, null);
    if (closing.closed()) {
      store.unlock(closing.driverId(), offerId);
      metrics.offerResult("expired");
      advance(closing.tripId());
    }
  }

  /**
   * The driver went offline or stopped reporting their position while holding an offer. Treated as
   * a rejection, at once: waiting out the timeout would only delay the rider.
   */
  void driverGone(UUID driverId) {
    store
        .lockedOffer(driverId)
        .ifPresent(
            offerId -> {
              OfferStore.Closing closing =
                  store.close(offerId, OfferStatus.REJECTED, OfferStatus.PENDING, driverId);
              if (closing.closed()) {
                store.unlock(driverId, offerId);
                metrics.offerResult("rejected");
                advance(closing.tripId());
              }
            });
  }

  // --- Recovery ------------------------------------------------------------------------------

  /**
   * Whether matching for this trip is still in hand: an offer out that has not overrun its expiry
   * by more than the grace period, or a search scheduled that is not overdue. Anything else - no
   * state at all, or state nobody will act on - means a crash or lost Redis data.
   */
  boolean isHealthy(UUID tripId, Instant now) {
    Optional<Match> match = store.match(tripId);
    if (match.isEmpty()) {
      return false;
    }
    Instant overdue = now.minusSeconds(rules.recoveryGraceSeconds());
    if (store.retryAt(tripId).filter(at -> at.isAfter(overdue)).isPresent()) {
      return true;
    }
    UUID offerId = match.get().currentOffer();
    return offerId != null
        && store
            .offer(offerId)
            .filter(offer -> offer.status().holdsDriver() && offer.expiresAt().isAfter(overdue))
            .isPresent();
  }

  /** Clears whatever is left of a trip's matching and starts it again from the database. */
  void restart(WaitingTrip trip) {
    store
        .match(trip.tripId())
        .map(Match::currentOffer)
        .ifPresent(
            offerId -> {
              OfferStore.Closing closing =
                  store.close(offerId, OfferStatus.EXPIRED, OfferStatus.PENDING, null);
              if (closing.closed()) {
                store.unlock(closing.driverId(), offerId);
              }
            });
    store.finish(trip.tripId());
    metrics.restart();
    begin(trip, null);
  }

  // --- Reads ---------------------------------------------------------------------------------

  /**
   * The offer the driver holds now, if any. WebSocket is best effort, so a driver who reconnects
   * mid-offer reads it here rather than never seeing it.
   */
  public Optional<OfferView> current(UUID driverId) {
    Instant now = clock.instant();
    return store
        .lockedOffer(driverId)
        .flatMap(store::offer)
        .filter(
            offer ->
                offer.driverId().equals(driverId)
                    && offer.status() == OfferStatus.PENDING
                    && offer.expiresAt().isAfter(now))
        .flatMap(
            offer ->
                store
                    .match(offer.tripId())
                    .map(
                        match ->
                            OfferView.of(
                                offer.offerId(),
                                match,
                                offer.expiresAt(),
                                rules.offerPickupRoundingDecimals())));
  }

  // --- Helpers -------------------------------------------------------------------------------

  private void advanceQuietly(UUID tripId) {
    try {
      advance(tripId);
    } catch (RuntimeException e) {
      // The driver's own action succeeded; the recovery sweep picks the trip up.
      log.warn("Could not move trip {} on to its next candidate", tripId, e);
    }
  }

  private static BusinessException rideNoLongerAvailable(Throwable cause) {
    return new BusinessException(
        "RIDE_NO_LONGER_AVAILABLE",
        HttpStatus.CONFLICT,
        "This ride is no longer available",
        Map.of(),
        cause);
  }

  /** For side effects that must not undo the step that caused them. */
  private static void bestEffort(String what, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn("Could not {}", what, e);
    }
  }
}
