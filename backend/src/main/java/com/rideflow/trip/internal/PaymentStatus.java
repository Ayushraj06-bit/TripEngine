package com.rideflow.trip.internal;

/** Where the money for a trip stands. Only UNPAID is used until payment arrives in Phase 7. */
public enum PaymentStatus {
  UNPAID,
  PAID,
  PENDING_SETTLEMENT,
  REFUNDED
}
