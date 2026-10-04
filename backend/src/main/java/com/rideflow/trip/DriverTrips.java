package com.rideflow.trip;

import java.util.Optional;
import java.util.UUID;

/**
 * A driver's current trip, as the database has it. {@code driver} and {@code location} ask this on
 * every trip event rather than trusting the event: events can arrive out of order, the answer here
 * cannot.
 */
public interface DriverTrips {

  /** Whether the driver has a trip assigned, arrived or in progress right now. */
  boolean hasActiveTrip(UUID driverId);

  /** That trip, if there is one. */
  Optional<ActiveTrip> activeTrip(UUID driverId);
}
