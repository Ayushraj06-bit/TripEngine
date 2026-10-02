package com.rideflow.trip;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trips waiting for a driver, as the database has them: what {@code matching} trusts, whatever
 * Redis has kept or lost.
 */
public interface WaitingTrips {

  /** The trip, if it is still waiting for a driver - REQUESTED - and empty otherwise. */
  Optional<WaitingTrip> waiting(UUID tripId);

  /** Trips that have been waiting since before {@code cutoff}, longest-waiting first. */
  List<WaitingTrip> waitingSince(Instant cutoff, int limit);
}
