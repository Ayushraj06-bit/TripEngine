package com.rideflow.driver;

/**
 * Whether a driver is taking work.
 *
 * <p>PROJECT_SPEC section 6: OFFLINE to AVAILABLE on going online, AVAILABLE to ON_TRIP on
 * assignment, back to AVAILABLE when the trip ends, and OFFLINE only from AVAILABLE. Going offline
 * while ON_TRIP is rejected.
 */
public enum DriverStatus {
  OFFLINE,
  AVAILABLE,
  ON_TRIP
}
