package com.rideflow.location.internal;

import com.rideflow.driver.DriverFlagging;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.ServiceArea;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

  private final DriverStateStore store;
  private final LiveTripRelay liveTrips;
  private final Clock clock;
  private final ServiceArea serviceArea;
  private final DriverFlagging driverFlagging;
  private final RideFlowProperties properties;
  private final MeterRegistry meters;
  private final Timer ingestTimer;

  LocationIngestService(
      DriverStateStore store,
      LiveTripRelay liveTrips,
      Clock clock,
      ServiceArea serviceArea,
      DriverFlagging driverFlagging,
      RideFlowProperties properties,
      MeterRegistry meters) {
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

    var outcome = store.applyUpdate(driverId, position, update.seq(), inServiceArea);

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
    if (strikes < threshold) {
      return;
    }
    log.warn("Driver {} flagged after {} implausible position jumps", driverId, strikes);
    driverFlagging.flagForReview(
        driverId, "Reported %d positions implying impossible speed".formatted(strikes));
  }

  private String count(String result) {
    meters.counter(COUNTER, RESULT_TAG, result).increment();
    return result;
  }
}
