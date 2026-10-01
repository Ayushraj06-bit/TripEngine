package com.rideflow.trip;

/** Who caused a change to a trip: the column "Who" in PROJECT_SPEC section 6. */
public enum TripActor {
  RIDER,
  DRIVER,
  /** RideFlow itself: matching assigning a driver, or giving up on finding one. */
  SYSTEM
}
