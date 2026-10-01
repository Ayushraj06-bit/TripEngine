package com.rideflow.trip;

import java.util.UUID;

/** What {@code driver} asks to keep a driver's status in line with their trips. */
public interface DriverTrips {

  /** Whether the driver has a trip assigned, arrived or in progress right now. */
  boolean hasActiveTrip(UUID driverId);
}
