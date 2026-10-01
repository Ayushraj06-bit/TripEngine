package com.rideflow.trip.internal;

import com.rideflow.trip.TripActor;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is asking: the authenticated user and the role their token carries, never ids from the
 * request body (PROJECT_SPEC section 10).
 */
public record Caller(UUID userId, TripActor role) {

  public Caller {
    Objects.requireNonNull(userId, "userId");
    if (role != TripActor.RIDER && role != TripActor.DRIVER) {
      throw new IllegalArgumentException("A caller is a rider or a driver, not " + role);
    }
  }
}
