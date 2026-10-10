package com.rideflow.payment.internal;

import java.util.Set;

/**
 * What a ledger transaction is for. With its reference it is unique: one of each per trip, top-up,
 * or refunded transaction, which is what makes every posting idempotent.
 */
public enum TransactionType {
  /** Reference: the top-up. */
  TOPUP,
  /** A wallet trip's fare. Reference: the trip. */
  TRIP_CHARGE,
  /** The commission a driver owes on a cash trip. Reference: the trip. */
  CASH_COMMISSION,
  /** Reference: the trip. */
  CANCELLATION_FEE,
  /** Reference: the transaction it reverses. */
  REFUND;

  /** What a rider paid from their wallet: the only money an admin can give back. */
  static final Set<TransactionType> REFUNDABLE = Set.of(TRIP_CHARGE, CANCELLATION_FEE);
}
