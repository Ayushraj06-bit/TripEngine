package com.rideflow.driver.internal;

import com.rideflow.driver.ApprovalStatus;
import com.rideflow.driver.DriverAdministration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.DriverStatusChanged;
import com.rideflow.shared.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin approval of drivers. */
@Service
class DriverApprovalService implements DriverAdministration {

  private static final Logger log = LoggerFactory.getLogger(DriverApprovalService.class);

  /** PROJECT_SPEC section 10 caps a page at 50. */
  private static final int MAX_LIMIT = 50;

  private final DriverRepository drivers;
  private final VehicleRepository vehicles;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  DriverApprovalService(
      DriverRepository drivers,
      VehicleRepository vehicles,
      ApplicationEventPublisher events,
      Clock clock) {
    this.drivers = drivers;
    this.vehicles = vehicles;
    this.events = events;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Page listByApprovalStatus(ApprovalStatus approvalStatus, UUID cursor, int limit) {
    int capped = Math.clamp(limit, 1, MAX_LIMIT);
    // One extra row, so the presence of a next page is known without a second count query.
    Limit fetch = Limit.of(capped + 1);

    List<Driver> found =
        cursor == null
            ? drivers.findByApprovalStatusOrderByUserIdAsc(approvalStatus, fetch)
            : drivers.findByApprovalStatusAndUserIdGreaterThanOrderByUserIdAsc(
                approvalStatus, cursor, fetch);

    boolean hasMore = found.size() > capped;
    List<Driver> page = hasMore ? found.subList(0, capped) : found;

    List<Entry> entries = page.stream().map(this::toEntry).toList();
    UUID nextCursor = hasMore ? page.getLast().userId() : null;
    return new Page(entries, nextCursor);
  }

  @Override
  @Transactional(noRollbackFor = BusinessException.class)
  public void approve(UUID driverId) {
    Driver driver = require(driverId);
    if (driver.approvalStatus() == ApprovalStatus.APPROVED) {
      // Idempotent: a double-click must not be an error.
      return;
    }
    driver.approve(clock.instant());
    log.info("Driver {} approved", driverId);
  }

  @Override
  @Transactional(noRollbackFor = BusinessException.class)
  public void reject(UUID driverId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(
          "REJECTION_REASON_REQUIRED",
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "A rejection must say why");
    }
    Driver driver = require(driverId);
    Instant now = clock.instant();
    DriverStatus previous = driver.status();

    if (driver.reject(reason, now)) {
      // The driver was on the map; rejection takes them off it.
      var vehicleType = vehicles.findByDriverId(driverId).map(Vehicle::type).orElse(null);
      // Flushed first, so the event carries the version this change gives the row.
      drivers.flush();
      events.publishEvent(
          new DriverStatusChanged(
              driverId,
              previous,
              driver.status(),
              vehicleType,
              vehicleType,
              now,
              driver.version()));
    }
    log.info("Driver {} rejected", driverId);
  }

  private Entry toEntry(Driver driver) {
    var vehicle = vehicles.findByDriverId(driver.userId());
    return new Entry(
        driver.userId(),
        driver.approvalStatus(),
        driver.status(),
        driver.rejectionReason(),
        vehicle.map(Vehicle::plateNumber).orElse(null),
        vehicle.map(v -> v.type().name()).orElse(null));
  }

  private Driver require(UUID driverId) {
    return drivers
        .findByUserId(driverId)
        .orElseThrow(() -> BusinessException.notFound("Driver not found"));
  }
}
