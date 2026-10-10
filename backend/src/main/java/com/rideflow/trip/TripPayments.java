package com.rideflow.trip;

import java.util.UUID;

/**
 * Records where the money for a trip stands. {@code payment} decides it and calls this inside its
 * own transaction, so the ledger and the trip commit together - or neither does.
 *
 * <p>Declared here and called from {@code payment}, which already depends on {@code trip} for its
 * events: {@code trip} never depends on {@code payment}.
 */
public interface TripPayments {

  /**
   * Sets the trip's payment status, and publishes {@link TripPaymentStatusChanged} if it changed.
   *
   * @throws IllegalStateException when called outside a transaction
   */
  void recordPaymentStatus(UUID tripId, PaymentStatus status);

  /** Marks every trip of the rider that was waiting on a top-up PAID: the wallet is in credit. */
  void settleOutstanding(UUID riderId);
}
