package com.rideflow.payment;

import java.time.Instant;
import java.util.UUID;

/** Giving a rider their money back, for {@code admin}. */
public interface Refunds {

  /**
   * Reverses a trip charge or cancellation fee with a new transaction that mirrors it: never by
   * changing the original, which the ledger forbids.
   *
   * @param refundedBy the admin, for the log
   * @throws com.rideflow.shared.BusinessException 404 for an unknown transaction; 422 {@code
   *     NOT_REFUNDABLE} for anything but a trip charge or a cancellation fee; 409 {@code
   *     ALREADY_REFUNDED} the second time
   */
  Refund refund(UUID transactionId, UUID refundedBy);

  record Refund(
      UUID refundTransactionId, UUID refundedTransactionId, long amountPaise, Instant refundedAt) {}
}
