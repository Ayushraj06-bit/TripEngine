package com.rideflow.shared;

/**
 * The kinds of vehicle RideFlow dispatches.
 *
 * <p>The name is used as the database enum value, as part of Redis keys such as {@code
 * drivers:available:{type}}, and in the API, so these constants must not be renamed without a
 * migration.
 */
public enum VehicleType {
  BIKE,
  AUTO,
  CAR
}
