package com.rideflow.location.internal;

import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.DriverArrived;
import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.DriverTrips;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripNoDriverFound;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripReturnedToMatching;
import com.rideflow.trip.TripStarted;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Keeps each driver's state hash in step with the trip they are on, so the update script can hand
 * it back with every position: whose rider to show it to, and whether to record the route.
 *
 * <p>Every trip event that concerns a driver is only a prompt to ask {@code trip} what the driver's
 * trip is now. Events can arrive late or out of order; the answer cannot. The Phase 4 driver-status
 * bug came from trusting the event.
 *
 * <p>Asking is not enough on its own: two events for one driver are handled at once, and the slower
 * answer could land last - start followed at once by complete would leave a finished trip attached,
 * and its rider watching the driver. So each write is checked by asking again, and redone if the
 * answer moved. The last write is then always confirmed by a read made after it, and any change
 * after that read brings an event whose own write comes later still.
 *
 * <p>No surrounding transaction: short reads, then Redis.
 */
@Component
class TripContextListener {

  /** Enough for any real burst; past it, the next event's listener settles the answer. */
  private static final int MAX_WRITES = 3;

  private final DriverTrips driverTrips;
  private final DriverStateStore store;

  TripContextListener(DriverTrips driverTrips, DriverStateStore store) {
    this.driverTrips = driverTrips;
    this.store = store;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripEvent event) {
    UUID driverId =
        switch (event) {
          case DriverAssigned assigned -> assigned.driverId();
          case DriverArrived arrived -> arrived.driverId();
          case TripStarted started -> started.driverId();
          case TripCompleted completed -> completed.driverId();
          case TripCancelled cancelled -> cancelled.driverId();
          case TripReturnedToMatching returned -> returned.previousDriverId();
          case TripRequested requested -> null;
          case TripNoDriverFound noDriver -> null;
        };
    if (driverId == null) {
      return;
    }
    Optional<ActiveTrip> trip = driverTrips.activeTrip(driverId);
    for (int write = 0; write < MAX_WRITES; write++) {
      store.applyTripContext(driverId, trip);
      Optional<ActiveTrip> now = driverTrips.activeTrip(driverId);
      if (now.equals(trip)) {
        return;
      }
      trip = now;
    }
  }
}
