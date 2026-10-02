package com.rideflow.matching.internal;

import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.DriverStatusChanged;
import com.rideflow.location.DriverWentStale;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripReturnedToMatching;
import com.rideflow.trip.TripStatus;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * What starts, redirects and stops matching.
 *
 * <p>No transaction around these, unlike the usual listener. Matching talks to Redis and to other
 * modules; a database connection held open across all that would starve the pool when a hundred
 * trips are requested at once, and PROJECT_SPEC section 11 keeps Redis out of database transactions
 * anyway. Each database call along the way takes its own short transaction.
 */
@Component
class MatchingListeners {

  private final Matcher matcher;

  MatchingListeners(Matcher matcher) {
    this.matcher = matcher;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripRequested event) {
    matcher.start(event.tripId(), null);
  }

  /** With priority: straight back into matching, and never to the driver who backed out. */
  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripReturnedToMatching event) {
    matcher.start(event.tripId(), event.previousDriverId());
  }

  /** Cancelled while still waiting for a driver: withdraw the offer that is out. */
  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripCancelled event) {
    if (event.previousStatus() == TripStatus.REQUESTED) {
      matcher.stop(event.tripId());
    }
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(DriverWentStale event) {
    matcher.driverGone(event.driverId());
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(DriverStatusChanged event) {
    if (event.newStatus() == DriverStatus.OFFLINE) {
      matcher.driverGone(event.driverId());
    }
  }
}
