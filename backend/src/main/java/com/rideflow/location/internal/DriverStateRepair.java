package com.rideflow.location.internal;

import com.rideflow.driver.DriverStatuses;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Restores a driver's status and trip from PostgreSQL after Redis lost them: a restart, a failover,
 * or the state hash's own expiry.
 *
 * <p>Both are written by events when they change, and losing Redis changes neither. Without this,
 * every driver online at the time stayed invisible to matching until they went offline and online
 * again, and every rider on a trip stopped seeing their car (found in the demo).
 */
@Component
class DriverStateRepair {

  private static final Logger log = LoggerFactory.getLogger(DriverStateRepair.class);

  /**
   * Every driver needs it at once after a restart, on the threads that take positions. The rest are
   * not queued: their next position, 3 s later, asks again.
   */
  private static final int AT_ONCE = 2;

  private final DriverStatuses statuses;
  private final TripContextListener trips;
  private final DriverStateStore store;
  private final MeterRegistry meters;
  private final Semaphore permits = new Semaphore(AT_ONCE);

  DriverStateRepair(
      DriverStatuses statuses,
      TripContextListener trips,
      DriverStateStore store,
      MeterRegistry meters) {
    this.statuses = statuses;
    this.trips = trips;
    this.store = store;
    this.meters = meters;
  }

  /** Not called from a transaction. A store that is away is logged, and left for next time. */
  void repair(UUID driverId) {
    if (!permits.tryAcquire()) {
      count("deferred");
      return;
    }
    try {
      // The status last: it is what says the state is whole, so a failure before it is retried.
      trips.follow(driverId);
      statuses
          .current(driverId)
          .ifPresent(
              now ->
                  store.applyStatusChange(
                      driverId, now.status().name(), now.vehicleType(), now.driverVersion()));
      count("restored");
      log.debug("Restored the state Redis had lost of driver {}", driverId);
    } catch (DataAccessException | TransactionException unavailable) {
      count("failed");
      log.warn(
          "Could not restore driver {}'s state; their next position tries again",
          driverId,
          unavailable);
    } finally {
      permits.release();
    }
  }

  /**
   * {@code driver_state_repairs_total}: a burst means Redis lost its data. The odd one is a driver
   * who reports before they have ever been online.
   */
  private void count(String result) {
    meters.counter("driver.state.repairs", "result", result).increment();
  }
}
