package com.rideflow.location.internal;

import com.rideflow.driver.DriverFlagging;
import com.rideflow.identity.Role;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.ServiceArea;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

/**
 * The validation pipeline for driver positions.
 *
 * <p>PROJECT_SPEC section 8 sets out the order, and every outcome is counted on {@code
 * location_updates_total} so a client misbehaving shows up as a shift in the mix rather than as
 * silence.
 *
 * <p>The split between here and Lua is deliberate. <strong>Stateless</strong> checks - range,
 * finiteness, accuracy, service area - live here: they need no previous position, no round trip,
 * and can be unit tested without Redis. <strong>Stateful</strong> checks - sequence, throttle,
 * implied speed, minimum move - live in {@code location_update.lua}, because each reads the state
 * the same call is about to overwrite, and split across two round trips two updates from one driver
 * could both pass before either wrote.
 */
@Service
public class LocationIngestService {

  private static final Logger log = LoggerFactory.getLogger(LocationIngestService.class);

  private static final String COUNTER = "location.updates";
  private static final String RESULT_TAG = "result";

  static final String REJECTED_INVALID = "rejected_invalid";
  static final String IGNORED_LOW_ACCURACY = "ignored_low_accuracy";
  static final String OUTSIDE_SERVICE_AREA = "outside_service_area";

  /** Redis could not take the position: timed out, refused, or its breaker is open. */
  static final String UNAVAILABLE = "unavailable";

  private final DriverStateStore store;
  private final LiveTripRelay liveTrips;
  private final Clock clock;
  private final ServiceArea serviceArea;
  private final DriverFlagging driverFlagging;
  private final RideFlowProperties properties;
  private final MeterRegistry meters;
  private final Timer ingestTimer;
  private final CircuitBreaker redisBreaker;

  LocationIngestService(
      DriverStateStore store,
      LiveTripRelay liveTrips,
      Clock clock,
      ServiceArea serviceArea,
      DriverFlagging driverFlagging,
      RideFlowProperties properties,
      MeterRegistry meters,
      CircuitBreakerRegistry breakers) {
    this.redisBreaker = breakers.circuitBreaker("redis-location");
    this.store = store;
    this.liveTrips = liveTrips;
    this.clock = clock;
    this.serviceArea = serviceArea;
    this.driverFlagging = driverFlagging;
    this.properties = properties;
    this.meters = meters;
    this.ingestTimer =
        Timer.builder("location.ingest")
            .description("Server-side time to accept or reject one driver position")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meters);
  }

  /**
   * A driver's app connected: its positions start again from {@code seq} zero.
   *
   * <p>Runs before the client receives CONNECTED, so before it can send its first position.
   */
  @EventListener
  void onConnected(SessionConnectedEvent event) {
    if (event.getUser() instanceof Authentication user
        && user.getAuthorities().stream()
            .anyMatch(authority -> Role.DRIVER.authority().equals(authority.getAuthority()))) {
      try {
        store.restartSequence(UUID.fromString(user.getName()));
      } catch (DataAccessException unavailable) {
        // Redis is away: the positions themselves will be dropped until it is back.
        log.warn("Could not restart driver {}'s position sequence", user.getName(), unavailable);
      }
    }
  }

  /**
   * Handles one reported position.
   *
   * <p>Never throws for a bad update. A driver streaming positions over a WebSocket cannot act on
   * an error, and tearing down the session over one malformed packet would lose the good ones too;
   * the outcome is counted instead.
   *
   * @return what happened, for the caller to log or test against
   */
  public String accept(UUID driverId, LocationUpdate update) {
    // Held in a Supplier so the compiler picks record(Supplier) rather than the
    // Runnable overload, which would discard the result.
    Supplier<String> work = () -> classify(driverId, update);
    return ingestTimer.record(work);
  }

  private String classify(UUID driverId, LocationUpdate update) {
    var limits = properties.location();

    // (a) Range and finiteness. GeoPoint validates in its constructor, so an invalid
    // pair cannot exist as a value - catching here is how that becomes a counter
    // rather than an exception on the WebSocket thread.
    GeoPoint position;
    try {
      position = new GeoPoint(update.lat(), update.lng());
    } catch (IllegalArgumentException e) {
      log.debug("Rejected position from driver {}: {}", driverId, e.getMessage());
      return count(REJECTED_INVALID);
    }
    if (update.seq() < 0 || !Double.isFinite(update.accuracyMeters())) {
      return count(REJECTED_INVALID);
    }

    // (b) A fix this imprecise would move the driver around the map on its own.
    if (update.accuracyMeters() > limits.maxAccuracyMeters()) {
      return count(IGNORED_LOW_ACCURACY);
    }

    // (f) Outside the city. Still recorded - the driver is driving and we want their
    // trail - but the script takes them out of the availability sets so they are not
    // offered work they cannot reach.
    boolean inServiceArea = serviceArea.contains(position);

    DriverStateStore.UpdateOutcome outcome;
    try {
      outcome =
          redisBreaker.executeSupplier(
              () -> store.applyUpdate(driverId, position, update.seq(), inServiceArea));
    } catch (CallNotPermittedException | DataAccessException unavailable) {
      // Dropped, not queued: the next position in 3 s replaces it, and a backlog would only
      // hold threads while Redis is away.
      return count(UNAVAILABLE);
    }

    if ("rejected_spoof".equals(outcome.result())) {
      flagIfPersistent(driverId, outcome.spoofStrikes());
    }
    if (outcome.accepted() && outcome.onTrip() != null) {
      liveTrips.relay(driverId, position, clock.instant(), outcome.onTrip());
    }

    // An accepted update from outside the area is counted separately, because "we
    // took it but you are not on the map" is a different operational signal from a
    // plain accept.
    String result = outcome.accepted() && !inServiceArea ? OUTSIDE_SERVICE_AREA : outcome.result();
    return count(result);
  }

  /**
   * Raises a driver for review once the implausible jumps stop looking like GPS drift.
   *
   * <p>One bad fix is a reflection off a building. Repeated ones are someone feeding the app a fake
   * location to farm trips in a busy area, which is why the threshold is configurable rather than
   * acting on the first strike.
   */
  private void flagIfPersistent(UUID driverId, int strikes) {
    int threshold = properties.location().spoofStrikesBeforeFlag();
    // As the strikes reach the threshold, then again at each multiple of it - not on every strike
    // past it, which was a database transaction and a warning per rejected position: in the
    // Phase 10 load test, a held connection pool and 111,000 log lines in ten minutes. Flagging
    // is idempotent; the repeats are there for an attempt the database did not take.
    if (strikes % threshold != 0) {
      return;
    }
    try {
      driverFlagging.flagForReview(
          driverId, "Reported %d positions implying impossible speed".formatted(strikes));
    } catch (DataAccessException | TransactionException unavailable) {
      log.warn(
          "Could not flag driver {} after {} implausible jumps; trying again after {} more",
          driverId,
          strikes,
          threshold,
          unavailable);
    }
  }

  private String count(String result) {
    meters.counter(COUNTER, RESULT_TAG, result).increment();
    return result;
  }
}
