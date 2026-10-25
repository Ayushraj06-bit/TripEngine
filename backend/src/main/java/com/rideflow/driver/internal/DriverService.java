package com.rideflow.driver.internal;

import com.rideflow.driver.ApprovalStatus;
import com.rideflow.driver.DriverFlagging;
import com.rideflow.driver.DriverRegistration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.DriverStatusChanged;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.DriverTrips;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A driver's own profile: vehicle registration and going online or offline. */
@Service
public class DriverService implements DriverRegistration, DriverFlagging {

  private static final Logger log = LoggerFactory.getLogger(DriverService.class);

  private final DriverRepository drivers;
  private final VehicleRepository vehicles;
  private final ApplicationEventPublisher events;
  private final IdGenerator ids;
  private final Clock clock;
  private final DriverTrips driverTrips;

  DriverService(
      DriverRepository drivers,
      VehicleRepository vehicles,
      ApplicationEventPublisher events,
      IdGenerator ids,
      Clock clock,
      DriverTrips driverTrips) {
    this.drivers = drivers;
    this.vehicles = vehicles;
    this.events = events;
    this.ids = ids;
    this.clock = clock;
    this.driverTrips = driverTrips;
  }

  /**
   * Called by {@code identity} inside the registration transaction.
   *
   * <p>Idempotent, so a retried registration cannot fail on a duplicate primary key.
   */
  @Override
  @Transactional
  public void createPendingProfile(UUID userId) {
    if (drivers.existsById(userId)) {
      return;
    }
    drivers.save(new Driver(userId, clock.instant()));
  }

  /**
   * Flags a driver for admin review, called by {@code location} on repeated position spoofing.
   *
   * <p>Deliberately does not change their status. Being flagged is a signal for a human, not an
   * automatic ban: a genuine GPS fault in a tunnel would otherwise throw a working driver off the
   * road mid-shift on the strength of a heuristic.
   */
  @Override
  @Transactional
  public void flagForReview(UUID driverId, String reason) {
    drivers
        .findByUserId(driverId)
        .ifPresent(
            driver -> {
              if (driver.flagForReview(reason, clock.instant())) {
                log.warn("Driver {} flagged for review: {}", driverId, reason);
              }
            });
  }

  /**
   * Registers or replaces the driver's vehicle.
   *
   * <p>Changing vehicle type while online has to move the driver between GEO sets, so the event
   * carries the previous type. Without that the driver would linger in the old type's set and be
   * offered the wrong kind of trip.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public VehicleView registerVehicle(
      UUID driverId, VehicleType type, String rawPlate, String model, String colour) {

    Driver driver = require(driverId);
    String plate = PlateNumber.normalise(rawPlate);

    vehicles
        .findByPlateNumber(plate)
        .filter(existing -> !existing.driverId().equals(driverId))
        .ifPresent(
            existing -> {
              throw new BusinessException(
                  "PLATE_ALREADY_REGISTERED",
                  HttpStatus.CONFLICT,
                  "That plate number is already registered to another driver");
            });

    Optional<Vehicle> current = vehicles.findByDriverId(driverId);
    VehicleType previousType = current.map(Vehicle::type).orElse(null);

    Vehicle vehicle =
        current
            .map(
                existing -> {
                  existing.update(type, plate, model, colour);
                  return existing;
                })
            .orElseGet(
                () ->
                    vehicles.save(new Vehicle(ids.newId(), driverId, type, plate, model, colour)));

    if (previousType != null && previousType != type && driver.status() != DriverStatus.OFFLINE) {
      // Already on the map under the old type: tell location to move them.
      events.publishEvent(
          new DriverStatusChanged(
              driverId, driver.status(), driver.status(), type, previousType, clock.instant()));
    }

    log.info("Driver {} registered vehicle {} ({})", driverId, plate, type);
    return VehicleView.of(vehicle);
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public StatusView goOnline(UUID driverId) {
    Driver driver = require(driverId);
    Instant now = clock.instant();
    Optional<Vehicle> vehicle = vehicles.findByDriverId(driverId);

    DriverStatus previous = driver.status();
    if (driver.goOnline(vehicle.isPresent(), now)) {
      publishStatusChange(driver, previous, vehicle.map(Vehicle::type).orElse(null), now);
    }
    return new StatusView(driver.status());
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public StatusView goOffline(UUID driverId) {
    Driver driver = require(driverId);
    Instant now = clock.instant();

    DriverStatus previous = driver.status();
    if (driver.goOffline(now)) {
      publishStatusChange(
          driver, previous, vehicles.findByDriverId(driverId).map(Vehicle::type).orElse(null), now);
    }
    return new StatusView(driver.status());
  }

  /**
   * Brings the driver's status in line with their trips: ON_TRIP while one holds them, AVAILABLE
   * once none does.
   *
   * <p>Asks {@code trip} for the current state rather than acting on which event arrived. Events
   * are delivered asynchronously and can be handled out of order: acting on them directly, a {@code
   * DriverAssigned} handled after its trip had already been handed back would leave the driver
   * ON_TRIP with no trip - unable to go offline, never offered work again. Reading the state makes
   * every call converge on the right answer, however many arrive and in whatever order.
   *
   * <p>The driver's row is locked before the trips are read. Unlocked, two calls at once lost an
   * update: one saw the trip still active and set ON_TRIP; the other, after the cancellation, saw
   * no trip but still the old AVAILABLE, changed nothing, and the ON_TRIP committed after it - a
   * driver held on a trip that no longer existed (found in CI). Locked, the second reads the trips
   * only once the first has committed.
   */
  @Transactional
  public void followTrips(UUID driverId) {
    Driver driver =
        drivers
            .findForUpdate(driverId)
            .orElseThrow(() -> BusinessException.notFound("Driver profile not found"));
    Instant now = clock.instant();
    DriverStatus previous = driver.status();
    boolean changed =
        driverTrips.hasActiveTrip(driverId) ? driver.startTrip(now) : driver.endTrip(now);
    if (changed) {
      // Location takes an ON_TRIP driver off the map, and puts them back when AVAILABLE.
      publishStatusChange(
          driver, previous, vehicles.findByDriverId(driverId).map(Vehicle::type).orElse(null), now);
    }
  }

  @Transactional(readOnly = true)
  public DriverProfile profile(UUID driverId) {
    Driver driver = require(driverId);
    return new DriverProfile(
        driver.userId(),
        driver.approvalStatus(),
        driver.status(),
        driver.rejectionReason(),
        driver.averageRating().isPresent() ? driver.averageRating().getAsDouble() : null,
        driver.ratingCount(),
        vehicles.findByDriverId(driverId).map(VehicleView::of).orElse(null));
  }

  private void publishStatusChange(
      Driver driver, DriverStatus previous, VehicleType vehicleType, Instant now) {
    // Published inside this transaction, so the Modulith registry commits it with the
    // state change. A listener that fails is retried, not lost.
    events.publishEvent(
        new DriverStatusChanged(
            driver.userId(), previous, driver.status(), vehicleType, vehicleType, now));
  }

  private Driver require(UUID driverId) {
    return drivers
        .findByUserId(driverId)
        // 404 rather than 403: PROJECT_SPEC section 10 never confirms that someone
        // else's resource exists.
        .orElseThrow(() -> BusinessException.notFound("Driver profile not found"));
  }

  /** Wrapped rather than returning the bare enum, so the response is a JSON object. */
  public record StatusView(DriverStatus status) {}

  public record VehicleView(VehicleType type, String plateNumber, String model, String colour) {
    static VehicleView of(Vehicle vehicle) {
      return new VehicleView(
          vehicle.type(), vehicle.plateNumber(), vehicle.model(), vehicle.colour());
    }
  }

  public record DriverProfile(
      UUID driverId,
      ApprovalStatus approvalStatus,
      DriverStatus status,
      String rejectionReason,
      Double averageRating,
      int ratingCount,
      VehicleView vehicle) {}
}
