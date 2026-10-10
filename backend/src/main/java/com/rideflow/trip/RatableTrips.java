package com.rideflow.trip;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** What {@code rating} needs to know about a trip: who was on it, and whether and when it ended. */
public interface RatableTrips {

  Optional<RatableTrip> find(UUID tripId);

  /**
   * @param driverId null if no driver was ever assigned
   * @param completedAt null unless the trip completed
   */
  record RatableTrip(
      UUID tripId, UUID riderId, UUID driverId, TripStatus status, Instant completedAt) {}
}
