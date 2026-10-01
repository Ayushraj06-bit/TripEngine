package com.rideflow.driver.internal;

import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import com.rideflow.trip.TripReturnedToMatching;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps a driver's status in step with their trips (PROJECT_SPEC section 6): ON_TRIP from
 * assignment, AVAILABLE again once the trip completes, is cancelled, or is handed back to matching.
 *
 * <p>Events rather than a call from {@code trip}, per PROJECT_SPEC section 5. Each event is only a
 * prompt to look again - see {@link DriverService#followTrips} for why the event itself is not
 * trusted. The status follows a moment after the trip commits; the partial unique index on {@code
 * trips.driver_id} is what stops a driver holding two trips in that moment, not this.
 */
@Component
class TripLifecycleListener {

  private final DriverService drivers;

  TripLifecycleListener(DriverService drivers) {
    this.drivers = drivers;
  }

  @ApplicationModuleListener
  void on(DriverAssigned event) {
    drivers.followTrips(event.driverId());
  }

  @ApplicationModuleListener
  void on(TripCompleted event) {
    drivers.followTrips(event.driverId());
  }

  @ApplicationModuleListener
  void on(TripCancelled event) {
    // A trip cancelled before assignment had no driver to free.
    if (event.driverId() != null) {
      drivers.followTrips(event.driverId());
    }
  }

  @ApplicationModuleListener
  void on(TripReturnedToMatching event) {
    drivers.followTrips(event.previousDriverId());
  }
}
