package com.rideflow.driver;

import java.util.UUID;

/**
 * Raises a driver for admin review.
 *
 * <p>Called by {@code location} when a driver's reported positions imply a speed above {@code
 * rideflow.location.max-speed-kmph} often enough to look deliberate rather than like GPS drift.
 *
 * <p>The flag lives on the {@code drivers} table because {@code driver} owns that row and an admin
 * has to be able to query it. {@code location} keeps only the strike counter, in Redis, where the
 * hot path already writes.
 */
public interface DriverFlagging {

  /**
   * Flags {@code driverId} for review, recording why.
   *
   * <p>Idempotent: flagging an already-flagged driver keeps the original reason and timestamp, so a
   * repeat offender does not lose the evidence of the first offence.
   */
  void flagForReview(UUID driverId, String reason);
}
