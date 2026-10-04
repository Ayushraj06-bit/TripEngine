package com.rideflow.pricing;

import com.rideflow.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/** What a trip owes under the rates it was priced with, for {@code trip}. */
public interface TripFees {

  /**
   * The cancellation fee on the rate card a trip was quoted with.
   *
   * <p>Looked up by the id the trip stored rather than by the card in force today: rate cards are
   * versioned and never edited, so this is the fee the rider agreed to when booking.
   */
  Money cancellationFee(UUID rateCardId);

  /**
   * The fare for a route under a stored rate card - the card the trip was quoted on, never the one
   * in force today - for repricing a finished trip whose route differed from the estimate.
   */
  FareBreakdown fareFor(
      UUID rateCardId, long distanceMeters, long durationSeconds, BigDecimal surgeMultiplier);

  /** The waiting rate per minute on the stored rate card. */
  Money waitingPerMinute(UUID rateCardId);
}
