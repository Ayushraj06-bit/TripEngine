package com.rideflow.trip;

/**
 * Where the money for a trip stands. {@code payment} decides it; {@code trip} records it (see
 * {@link TripPayments}).
 */
public enum PaymentStatus {
  UNPAID,
  PAID,
  PENDING_SETTLEMENT,
  REFUNDED
}
