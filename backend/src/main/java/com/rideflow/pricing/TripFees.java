package com.rideflow.pricing;

import com.rideflow.shared.Money;
import java.util.UUID;

/** The fees a trip owes under the rates it was priced with, for {@code trip}. */
public interface TripFees {

  /**
   * The cancellation fee on the rate card a trip was quoted with.
   *
   * <p>Looked up by the id the trip stored rather than by the card in force today: rate cards are
   * versioned and never edited, so this is the fee the rider agreed to when booking.
   */
  Money cancellationFee(UUID rateCardId);
}
