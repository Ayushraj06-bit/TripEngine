package com.rideflow.driver;

import java.util.List;
import java.util.UUID;

/**
 * The approval operations the {@code admin} module calls.
 *
 * <p>The rule lives here because {@code driver} owns {@code approval_status}; only the admin-facing
 * endpoint lives in {@code admin}. That keeps PROJECT_SPEC section 5's boundaries intact - {@code
 * admin} never touches a driver table directly.
 */
public interface DriverAdministration {

  /**
   * One page of drivers in the given approval state, ordered oldest first.
   *
   * <p>Cursor pagination per PROJECT_SPEC section 10. The cursor is the last driver id from the
   * previous page: driver ids are UUIDv7 and therefore already chronological, so no composite or
   * opaque cursor is needed.
   *
   * @param cursor null for the first page
   * @param limit capped at 50 by section 10
   */
  Page listByApprovalStatus(ApprovalStatus approvalStatus, UUID cursor, int limit);

  /**
   * Approves a driver.
   *
   * <p>Idempotent: approving an already-approved driver changes nothing and does not fail.
   * Re-approving a rejected driver is allowed - see ADR 0005.
   */
  void approve(UUID driverId);

  /**
   * Rejects a driver, recording why.
   *
   * <p>Forces the driver offline, so a rejected driver cannot stay on the live map.
   */
  void reject(UUID driverId, String reason);

  /** A page of results plus the cursor to pass for the next one. */
  record Page(List<Entry> drivers, UUID nextCursor) {}

  /** What an admin needs to see in a review queue. No phone number: that belongs to identity. */
  record Entry(
      UUID driverId,
      ApprovalStatus approvalStatus,
      DriverStatus status,
      String rejectionReason,
      String vehiclePlateNumber,
      String vehicleType) {}
}
