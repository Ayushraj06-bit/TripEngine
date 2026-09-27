package com.rideflow.driver;

/** Where a driver is in admin review. A driver may only go online once APPROVED. */
public enum ApprovalStatus {
  PENDING,
  APPROVED,
  REJECTED
}
