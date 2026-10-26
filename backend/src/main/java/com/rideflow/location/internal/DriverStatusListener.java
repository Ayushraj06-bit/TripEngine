package com.rideflow.location.internal;

import com.rideflow.driver.DriverStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the live map in step with the driver module.
 *
 * <p>{@link ApplicationModuleListener} means this runs asynchronously, after the publishing
 * transaction commits, and that the Modulith registry recorded the event in that same commit. So if
 * Redis is down when a driver goes online, the event is retried rather than lost - the alternative,
 * a direct call from {@code driver}, would put a Redis round trip inside a database transaction,
 * which PROJECT_SPEC section 11 forbids, and would silently drop the change on failure.
 *
 * <p>The asynchrony is why a driver appears on the map a moment after going online rather than
 * instantly. That is the right trade: a brief delay before receiving offers costs nothing, whereas
 * a driver who is ON_TRIP but still listed as available would be offered a second trip.
 */
@Component
class DriverStatusListener {

  private static final Logger log = LoggerFactory.getLogger(DriverStatusListener.class);

  private final DriverStateStore store;

  DriverStatusListener(DriverStateStore store) {
    this.store = store;
  }

  @ApplicationModuleListener
  void on(DriverStatusChanged event) {
    log.debug(
        "Driver {} moved from {} to {}",
        event.driverId(),
        event.previousStatus(),
        event.newStatus());

    store.applyStatusChange(
        event.driverId(), event.newStatus().name(), event.vehicleType(), event.driverVersion());
  }
}
