package com.rideflow.driver;

import java.util.UUID;

/** The driver's rating totals, kept by {@code driver} and fed by {@code rating}. */
public interface DriverRatings {

  /**
   * Adds one rating to the driver's sum and count, in the caller's transaction, so the rating and
   * the totals commit together. The average is sum / count: no AVG over ratings on the hot path.
   */
  void addRating(UUID driverId, int stars);
}
