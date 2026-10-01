package com.rideflow.trip;

import com.rideflow.shared.GeoPoint;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a driver is now, for the arrival geofence.
 *
 * <p>Declared here and implemented by {@code location}, not the other way round. {@code driver}
 * reacts to trip events and {@code location} to driver events, so {@code trip} depending on {@code
 * location} would close a cycle that Spring Modulith rejects.
 */
public interface DriverPositions {

  /**
   * The driver's last reported position, if it is recent enough to trust: reported within {@code
   * rideflow.location.stale-after-seconds}. Empty when unknown or stale - a stale fix that happens
   * to lie inside the geofence must not count as arrived.
   */
  Optional<GeoPoint> currentPosition(UUID driverId);
}
