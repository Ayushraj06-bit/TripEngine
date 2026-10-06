package com.rideflow.trip.internal;

import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.TripFees;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.KeyedRateLimiter;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.DriverArrived;
import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.DriverPositions;
import com.rideflow.trip.DriverTrips;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.RoutePoint;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripAssignment;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripNoDriverFound;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripReturnedToMatching;
import com.rideflow.trip.TripRoutes;
import com.rideflow.trip.TripStarted;
import com.rideflow.trip.TripStatus;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything that happens to a trip after it is booked, and every read of one.
 *
 * <p>Each change runs in one transaction that updates the trip, appends one {@code trip_events} row
 * and publishes one {@link TripEvent} - all in {@link #record}, so no path can do one without the
 * others.
 *
 * <p>User actions use a {@link TransactionTemplate} rather than {@code @Transactional}, because an
 * optimistic-lock conflict only surfaces at commit, and turning it into a useful 409 has to happen
 * outside the transaction that failed.
 */
@Service
public class TripService implements TripAssignment, DriverTrips {

  private static final Logger log = LoggerFactory.getLogger(TripService.class);

  static final String ONE_ACTIVE_TRIP_PER_DRIVER = "trips_one_active_per_driver";

  private static final List<TripStatus> ACTIVE =
      Arrays.stream(TripStatus.values()).filter(TripStatus::isActive).toList();

  /** The states in which a trip holds its driver: {@code trips_one_active_per_driver}'s. */
  private static final List<TripStatus> HOLDING_A_DRIVER =
      List.of(TripStatus.DRIVER_ASSIGNED, TripStatus.DRIVER_ARRIVED, TripStatus.IN_PROGRESS);

  private static final SecureRandom OTP_RANDOM = new SecureRandom();

  private final TripRepository trips;
  private final EntityManager entityManager;
  private final ApplicationEventPublisher events;
  private final DriverPositions positions;
  private final TripFees fees;
  private final TripRoutes routes;
  private final FinalFares finalFares;
  private final IdGenerator ids;
  private final Clock clock;
  private final RideFlowProperties properties;
  private final JsonMapper json;
  private final TransactionTemplate transactions;

  TripService(
      TripRepository trips,
      EntityManager entityManager,
      ApplicationEventPublisher events,
      DriverPositions positions,
      TripFees fees,
      TripRoutes routes,
      FinalFares finalFares,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties,
      JsonMapper json,
      PlatformTransactionManager transactionManager) {
    this.trips = trips;
    this.entityManager = entityManager;
    this.events = events;
    this.positions = positions;
    this.fees = fees;
    this.routes = routes;
    this.finalFares = finalFares;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.json = json;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  // --- Reads ---------------------------------------------------------------------------------

  /** The caller's trip in progress, if any. Clients call this after every (re)connect. */
  @Transactional(readOnly = true)
  public Optional<TripView> active(Caller caller) {
    Optional<Trip> trip =
        caller.role() == TripActor.RIDER
            ? trips.findFirstByRiderIdAndStatusIn(caller.userId(), ACTIVE)
            : trips.findFirstByDriverIdAndStatusIn(caller.userId(), ACTIVE);
    return trip.map(t -> t.view(caller.role()));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasActiveTrip(UUID driverId) {
    return trips.findFirstByDriverIdAndStatusIn(driverId, HOLDING_A_DRIVER).isPresent();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ActiveTrip> activeTrip(UUID driverId) {
    return trips.findFirstByDriverIdAndStatusIn(driverId, HOLDING_A_DRIVER).map(Trip::activeTrip);
  }

  /**
   * @throws BusinessException 404 unless the caller is the trip's rider or assigned driver
   */
  @Transactional(readOnly = true)
  public TripView get(UUID tripId, Caller caller) {
    Trip trip = load(tripId);
    return trip.view(partOf(trip, caller));
  }

  /**
   * The caller's trips, newest first.
   *
   * @param cursor the last trip id of the previous page, or null for the first page
   */
  @Transactional(readOnly = true)
  public TripPage history(Caller caller, UUID cursor, int limit) {
    boolean rider = caller.role() == TripActor.RIDER;
    Limit fetch = Limit.of(limit + 1);
    List<Trip> page;
    if (cursor == null) {
      page =
          rider
              ? trips.findByRiderIdOrderByRequestedAtDescIdDesc(caller.userId(), fetch)
              : trips.findByDriverIdOrderByRequestedAtDescIdDesc(caller.userId(), fetch);
    } else {
      Trip last =
          trips
              .findById(cursor)
              .filter(trip -> isPartOf(trip, caller))
              // Another user's trip id reads exactly like a made-up one.
              .orElseThrow(
                  () ->
                      new BusinessException(
                          "INVALID_CURSOR", HttpStatus.BAD_REQUEST, "Unknown cursor"));
      page =
          rider
              ? trips.findRiderHistoryAfter(caller.userId(), last.requestedAt(), last.id(), fetch)
              : trips.findDriverHistoryAfter(caller.userId(), last.requestedAt(), last.id(), fetch);
    }

    boolean more = page.size() > limit;
    List<Trip> shown = more ? page.subList(0, limit) : page;
    return new TripPage(
        shown.stream().map(trip -> trip.view(caller.role())).toList(),
        more ? shown.getLast().id() : null);
  }

  // --- Booking ---------------------------------------------------------------------------------

  /**
   * Saves a new REQUESTED trip. Called by {@link BookingService} inside the transaction that also
   * stores the idempotent response, so both commit or neither does.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public TripView request(UUID riderId, ConsumedQuote quote, PaymentMethod paymentMethod) {
    Instant now = clock.instant();
    Trip trip = Trip.request(ids.newId(), riderId, quote, paymentMethod, now);
    trips.save(trip);
    record(
        trip,
        null,
        TripStatus.REQUESTED,
        TripActor.RIDER,
        riderId,
        now,
        Map.of("quoteId", quote.quoteId()),
        new TripRequested(
            ids.newId(),
            now,
            trip.id(),
            riderId,
            trip.vehicleType(),
            trip.pickup().latitude(),
            trip.pickup().longitude(),
            trip.drop().latitude(),
            trip.drop().longitude(),
            trip.quotedFarePaise(),
            trip.surgeMultiplier(),
            paymentMethod));
    return trip.view(TripActor.RIDER);
  }

  // --- User actions ----------------------------------------------------------------------------

  /**
   * Cancels, by the rider or the assigned driver, under PROJECT_SPEC section 6.
   *
   * <p>Cancelling a cancelled trip succeeds and changes nothing: a client that never saw the first
   * response will retry, and must not be told off for it.
   */
  public TripView cancel(UUID tripId, Caller caller, String reason) {
    return reportingConflicts(
        tripId,
        () -> {
          Trip trip = load(tripId);
          TripActor actor = partOf(trip, caller);
          if (trip.status() == TripStatus.CANCELLED) {
            return trip.view(actor);
          }
          TripStatus from = trip.status();
          TripStatus to = TripStateMachine.next(from, TripAction.CANCEL, actor);
          Instant now = clock.instant();

          if (to == TripStatus.REQUESTED) {
            UUID previousDriver = trip.returnToMatching();
            record(
                trip,
                from,
                to,
                actor,
                caller.userId(),
                now,
                Map.of("reason", reason),
                new TripReturnedToMatching(
                    ids.newId(),
                    now,
                    trip.id(),
                    trip.riderId(),
                    previousDriver,
                    trip.vehicleType(),
                    trip.pickup().latitude(),
                    trip.pickup().longitude()));
            return trip.view(actor);
          }

          if (actor == TripActor.DRIVER) {
            requireNoShowWaitOver(trip, now);
          }
          Money fee = cancellationFee(trip, from, actor, now);
          trip.cancel(actor, reason, fee, now);
          record(
              trip,
              from,
              to,
              actor,
              caller.userId(),
              now,
              Map.of("reason", reason, "cancellationFeePaise", fee.paise()),
              new TripCancelled(
                  ids.newId(),
                  now,
                  trip.id(),
                  trip.riderId(),
                  trip.driverId(),
                  actor,
                  from,
                  fee.paise(),
                  trip.paymentMethod()));
          return trip.view(actor);
        });
  }

  /**
   * The driver says they are at the pickup point, and the location module has to agree.
   *
   * @throws BusinessException 422 {@code POSITION_UNKNOWN} without a recent position, 422 {@code
   *     NOT_AT_PICKUP} outside {@code arrival-geofence-meters}
   */
  public TripView arrive(UUID tripId, Caller caller) {
    // A Redis read, so it happens before the transaction opens (PROJECT_SPEC section 11).
    Optional<GeoPoint> position = positions.currentPosition(caller.userId());
    return reportingConflicts(
        tripId,
        () -> {
          Trip trip = load(tripId);
          TripActor actor = partOf(trip, caller);
          TripStatus from = trip.status();
          TripStatus to = TripStateMachine.next(from, TripAction.ARRIVE, actor);

          GeoPoint here =
              position.orElseThrow(
                  () ->
                      BusinessException.businessRule(
                          "POSITION_UNKNOWN",
                          "We have not had your location recently. Check that location sharing"
                              + " is on."));
          long meters = Math.round(here.distanceMetersTo(trip.pickup()));
          if (meters > properties.trip().arrivalGeofenceMeters()) {
            throw new BusinessException(
                "NOT_AT_PICKUP",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "You are %d m from the pickup point".formatted(meters),
                Map.of("distanceMeters", meters));
          }

          Instant now = clock.instant();
          trip.arrive(now);
          record(
              trip,
              from,
              to,
              actor,
              caller.userId(),
              now,
              Map.of("distanceMeters", meters),
              new DriverArrived(ids.newId(), now, trip.id(), trip.riderId(), trip.driverId()));
          return trip.view(actor);
        });
  }

  /**
   * Starts the trip with the OTP the rider reads out.
   *
   * <p>A wrong code is counted in a transaction that commits, and only then rejected. Were it
   * rejected from inside the transaction, the count would roll back with it and the lock would
   * never engage - the trap Phase 1's account lockout fell into.
   *
   * @throws BusinessException 422 {@code OTP_INVALID}; 429 {@code OTP_LOCKED} after {@code
   *     otp-max-attempts} wrong codes, for {@code otp-lock-minutes}, even for the right code
   */
  public TripView start(UUID tripId, Caller caller, String otp) {
    StartAttempt attempt =
        reportingConflicts(
            tripId,
            () -> {
              Trip trip = load(tripId);
              TripActor actor = partOf(trip, caller);
              TripStatus from = trip.status();
              TripStatus to = TripStateMachine.next(from, TripAction.START, actor);
              Instant now = clock.instant();

              if (trip.isOtpLockedAt(now)) {
                return StartAttempt.locked(trip.otpLockedUntil());
              }
              if (!trip.otpMatches(otp)) {
                var rules = properties.trip();
                boolean locked =
                    trip.recordWrongOtp(
                        now, rules.otpMaxAttempts(), Duration.ofMinutes(rules.otpLockMinutes()));
                return locked
                    ? StartAttempt.locked(trip.otpLockedUntil())
                    : StartAttempt.wrong(rules.otpMaxAttempts() - trip.otpAttempts());
              }

              trip.start(now);
              record(
                  trip,
                  from,
                  to,
                  actor,
                  caller.userId(),
                  now,
                  Map.of(),
                  new TripStarted(ids.newId(), now, trip.id(), trip.riderId(), trip.driverId()));
              return StartAttempt.started(trip.view(actor));
            });
    return attempt.orThrow(clock.instant());
  }

  /**
   * Completes the trip at its final fare: the quote, repriced if the route driven was far from the
   * estimate, plus waiting (see {@link FinalFares}).
   */
  public TripView complete(UUID tripId, Caller caller) {
    // A Redis read, so before the transaction opens (PROJECT_SPEC section 11).
    List<RoutePoint> route = recordedRoute(tripId);
    TripView completed =
        reportingConflicts(
            tripId,
            () -> {
              Trip trip = load(tripId);
              TripActor actor = partOf(trip, caller);
              TripStatus from = trip.status();
              TripStatus to = TripStateMachine.next(from, TripAction.COMPLETE, actor);
              Instant now = clock.instant();
              FinalFares.FinalFare fare = finalFares.settle(trip, route, now);
              trip.complete(now, fare);
              if (fare.flag() != null) {
                trips.flag(trip.id(), fare.flag());
              }
              record(
                  trip,
                  from,
                  to,
                  actor,
                  caller.userId(),
                  now,
                  Map.of(
                      "finalFarePaise", fare.farePaise(),
                      "distanceMeters", fare.distanceMeters(),
                      "durationSeconds", fare.durationSeconds(),
                      "repriced", fare.repriced(),
                      "waitingChargePaise", fare.waitingChargePaise()),
                  new TripCompleted(
                      ids.newId(),
                      now,
                      trip.id(),
                      trip.riderId(),
                      trip.driverId(),
                      fare.farePaise(),
                      trip.paymentMethod()));
              return trip.view(actor);
            });
    try {
      // The route has done its job; its TTL would clear it anyway.
      routes.forget(tripId);
    } catch (RuntimeException e) {
      log.warn("Could not delete the route of trip {}", tripId, e);
    }
    return completed;
  }

  /**
   * The route driven, or none when Redis cannot say: a driver must be able to finish a trip while
   * Redis is down, and with no route the quote stands and the trip is flagged for review.
   */
  private List<RoutePoint> recordedRoute(UUID tripId) {
    try {
      return routes.route(tripId);
    } catch (RuntimeException e) {
      log.warn("Could not read the route of trip {}; charging the quoted fare", tripId, e);
      return List.of();
    }
  }

  /**
   * Adds a reason to the trip's flag for review, keeping any already there.
   *
   * @return true if this call added it
   */
  boolean flag(UUID tripId, String reason) {
    return Boolean.TRUE.equals(transactions.execute(status -> trips.flag(tripId, reason) == 1));
  }

  // --- System actions (TripAssignment) ---------------------------------------------------------

  @Override
  public void assignDriver(UUID tripId, UUID driverId) {
    try {
      retryOnceOnConflict(
          () ->
              transactions.executeWithoutResult(
                  status -> {
                    Trip trip = load(tripId);
                    if (trip.status() == TripStatus.DRIVER_ASSIGNED
                        && driverId.equals(trip.driverId())) {
                      return;
                    }
                    TripStatus from = trip.status();
                    TripStatus to =
                        TripStateMachine.next(from, TripAction.ASSIGN_DRIVER, TripActor.SYSTEM);
                    Instant now = clock.instant();
                    trip.assign(driverId, newOtp(), now);
                    record(
                        trip,
                        from,
                        to,
                        TripActor.SYSTEM,
                        null,
                        now,
                        Map.of("driverId", driverId),
                        new DriverAssigned(ids.newId(), now, trip.id(), trip.riderId(), driverId));
                  }));
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      if (violated(e, ONE_ACTIVE_TRIP_PER_DRIVER)) {
        throw new BusinessException(
            "DRIVER_HAS_ACTIVE_TRIP",
            HttpStatus.CONFLICT,
            "That driver is already on another trip",
            Map.of(),
            e);
      }
      throw e;
    }
  }

  @Override
  public void markNoDriverFound(UUID tripId) {
    retryOnceOnConflict(
        () ->
            transactions.executeWithoutResult(
                status -> {
                  Trip trip = load(tripId);
                  if (trip.status() == TripStatus.NO_DRIVER_FOUND) {
                    return;
                  }
                  TripStatus from = trip.status();
                  TripStatus to =
                      TripStateMachine.next(
                          from, TripAction.MARK_NO_DRIVER_FOUND, TripActor.SYSTEM);
                  Instant now = clock.instant();
                  trip.noDriverFound(now);
                  record(
                      trip,
                      from,
                      to,
                      TripActor.SYSTEM,
                      null,
                      now,
                      Map.of(),
                      new TripNoDriverFound(ids.newId(), now, trip.id(), trip.riderId()));
                }));
  }

  // --- Internals -------------------------------------------------------------------------------

  /**
   * The one place a change is recorded: the audit row and the event, in the caller's transaction,
   * so the event reaches the Modulith registry - the outbox - in the same commit as the change.
   */
  private void record(
      Trip trip,
      TripStatus from,
      TripStatus to,
      TripActor actor,
      UUID actorId,
      Instant now,
      Map<String, ?> metadata,
      TripEvent event) {
    if (trip.status() != to) {
      throw new IllegalStateException(
          "Trip %s is %s, but the transition table says %s"
              .formatted(trip.id(), trip.status(), to));
    }
    entityManager.persist(
        new TripEventRow(
            ids.newId(),
            trip.id(),
            from,
            to,
            actor,
            actorId,
            now,
            json.writeValueAsString(metadata)));
    events.publishEvent(event);
  }

  /**
   * Runs a user action in a transaction. If someone else changed the trip first, answers 409 with
   * the state it is in now, so the client can redraw instead of guessing.
   */
  private <T> T reportingConflicts(UUID tripId, Supplier<T> action) {
    try {
      return transactions.execute(status -> action.get());
    } catch (OptimisticLockingFailureException e) {
      TripStatus now = transactions.execute(status -> load(tripId).status());
      throw new BusinessException(
          "TRIP_CONCURRENT_UPDATE",
          HttpStatus.CONFLICT,
          "The trip changed while you were acting on it. It is now " + now + ".",
          Map.of("tripStatus", now),
          e);
    }
  }

  /**
   * System transitions are idempotent and reload the trip, so after a conflict one retry sees the
   * new state and either finds its work already done or is refused by the state machine.
   */
  static void retryOnceOnConflict(Runnable action) {
    try {
      action.run();
    } catch (OptimisticLockingFailureException first) {
      try {
        action.run();
      } catch (RuntimeException second) {
        second.addSuppressed(first);
        throw second;
      }
    }
  }

  /** Whether {@code failure} was caused by the named constraint or unique index. */
  static boolean violated(Throwable failure, String constraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
          && constraint.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }

  private Trip load(UUID tripId) {
    return trips.findById(tripId).orElseThrow(() -> BusinessException.notFound("Trip not found"));
  }

  /**
   * The caller's part in this trip. Anyone else gets a 404 identical to a missing trip, so probing
   * ids cannot confirm that another user's trip exists (PROJECT_SPEC section 10).
   */
  private static TripActor partOf(Trip trip, Caller caller) {
    if (!isPartOf(trip, caller)) {
      throw BusinessException.notFound("Trip not found");
    }
    return caller.role();
  }

  private static boolean isPartOf(Trip trip, Caller caller) {
    UUID party = caller.role() == TripActor.RIDER ? trip.riderId() : trip.driverId();
    return caller.userId().equals(party);
  }

  /**
   * PROJECT_SPEC section 6: free while no driver is on the way or within the grace period after
   * assignment; the rate card's cancellation fee after that, and for a no-show.
   */
  private Money cancellationFee(Trip trip, TripStatus from, TripActor actor, Instant now) {
    boolean free =
        from == TripStatus.REQUESTED
            || (from == TripStatus.DRIVER_ASSIGNED
                && actor == TripActor.RIDER
                && !now.isAfter(
                    trip.assignedAt().plusSeconds(properties.trip().riderCancelGraceSeconds())));
    return free ? Money.ofPaise(0) : fees.cancellationFee(trip.rateCardId());
  }

  /** A driver may give up on a rider only after waiting {@code no-show-after-minutes}. */
  private void requireNoShowWaitOver(Trip trip, Instant now) {
    Instant allowedFrom =
        trip.arrivedAt().plus(Duration.ofMinutes(properties.trip().noShowAfterMinutes()));
    if (now.isBefore(allowedFrom)) {
      throw new BusinessException(
          "NO_SHOW_TOO_EARLY",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "You can cancel for a no-show after waiting %d minutes"
              .formatted(properties.trip().noShowAfterMinutes()),
          Map.of("canCancelAt", allowedFrom));
    }
  }

  private static String newOtp() {
    return "%04d".formatted(OTP_RANDOM.nextInt(10_000));
  }

  /** How a start attempt ended, carried out of the transaction so a rejection can still commit. */
  private record StartAttempt(TripView started, Instant lockedUntil, int attemptsLeft) {

    static StartAttempt started(TripView trip) {
      return new StartAttempt(trip, null, 0);
    }

    static StartAttempt locked(Instant until) {
      return new StartAttempt(null, until, 0);
    }

    static StartAttempt wrong(int attemptsLeft) {
      return new StartAttempt(null, null, attemptsLeft);
    }

    TripView orThrow(Instant now) {
      if (started != null) {
        return started;
      }
      if (lockedUntil != null) {
        // Rounded up, as every Retry-After is: a client told to come back a moment early
        // would only be refused again.
        long seconds =
            Math.max(1, Math.ceilDiv(Duration.between(now, lockedUntil).toMillis(), 1000));
        throw new BusinessException(
            "OTP_LOCKED",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many wrong codes. Try again in %d seconds.".formatted(seconds),
            Map.of(KeyedRateLimiter.RETRY_AFTER_SECONDS, seconds));
      }
      throw new BusinessException(
          "OTP_INVALID",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "That code is not right",
          Map.of("attemptsRemaining", attemptsLeft));
    }
  }
}
