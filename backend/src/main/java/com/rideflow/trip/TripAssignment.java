package com.rideflow.trip;

import java.util.UUID;

/**
 * The system's side of the trip lifecycle, for {@code matching} (Phase 5).
 *
 * <p>Both calls are idempotent: repeating one that already happened changes nothing and publishes
 * nothing, so a retried offer acceptance or deadline cannot assign twice or fail.
 */
public interface TripAssignment {

  /**
   * REQUESTED to DRIVER_ASSIGNED, with a new OTP for the rider.
   *
   * @throws com.rideflow.shared.BusinessException 409 {@code TRIP_INVALID_TRANSITION} if the trip
   *     is no longer waiting (for example the rider cancelled), 409 {@code DRIVER_HAS_ACTIVE_TRIP}
   *     if the driver is already on another trip, 404 if there is no such trip
   */
  void assignDriver(UUID tripId, UUID driverId);

  /**
   * REQUESTED to NO_DRIVER_FOUND, when matching's deadline passes.
   *
   * @throws com.rideflow.shared.BusinessException 409 {@code TRIP_INVALID_TRANSITION} if the trip
   *     is no longer waiting, 404 if there is no such trip
   */
  void markNoDriverFound(UUID tripId);
}
