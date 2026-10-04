package com.rideflow.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trips flagged for a human to review, for {@code admin}. */
public interface FlaggedTrips {

  /**
   * Newest first, cursor paginated (PROJECT_SPEC section 10).
   *
   * @param cursor the last trip id of the previous page, or null for the first
   */
  Page list(UUID cursor, int limit);

  record Page(List<FlaggedTrip> trips, UUID nextCursor) {}

  /**
   * @param finalFarePaise null until the trip completes
   */
  record FlaggedTrip(
      UUID tripId,
      TripStatus status,
      UUID riderId,
      UUID driverId,
      String flaggedReason,
      long quotedFarePaise,
      Long finalFarePaise,
      long distanceMeters,
      long durationSeconds,
      Instant requestedAt,
      Instant completedAt) {}
}
