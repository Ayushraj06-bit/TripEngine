package com.rideflow.trip;

/**
 * Where a trip is in its life (PROJECT_SPEC section 6).
 *
 * <p>{@link #COMPLETED}, {@link #CANCELLED} and {@link #NO_DRIVER_FOUND} are terminal.
 */
public enum TripStatus {
  REQUESTED,
  DRIVER_ASSIGNED,
  DRIVER_ARRIVED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED,
  NO_DRIVER_FOUND;

  /** True while the trip holds its rider: every state but the terminal ones. */
  public boolean isActive() {
    return this != COMPLETED && this != CANCELLED && this != NO_DRIVER_FOUND;
  }
}
