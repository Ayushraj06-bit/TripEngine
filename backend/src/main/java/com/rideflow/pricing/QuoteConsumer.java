package com.rideflow.pricing;

import com.rideflow.shared.VehicleType;
import java.util.UUID;

/** How {@code trip} turns a quote into a booking. */
public interface QuoteConsumer {

  /**
   * Uses a quote, so it cannot be booked twice.
   *
   * <p>Atomic: of any number of concurrent calls for one quote, exactly one succeeds. The ownership
   * and vehicle-type checks happen <em>before</em> anything is deleted, so a caller holding someone
   * else's quote id cannot destroy it.
   *
   * @throws com.rideflow.shared.BusinessException 410 {@code QUOTE_EXPIRED} if it is missing,
   *     expired or already used; 404 {@code QUOTE_NOT_FOUND} if it belongs to another rider; 422
   *     {@code QUOTE_VEHICLE_TYPE_MISMATCH} if it was priced for a different vehicle type
   */
  ConsumedQuote consume(UUID quoteId, UUID riderId, VehicleType vehicleType);

  /**
   * Puts a consumed quote back, for a booking whose transaction failed after consuming it.
   *
   * <p>Keeps the original expiry, measured on the injected clock, so a restore never extends a
   * quote's life. A quote already past its expiry is not restored.
   */
  void restore(ConsumedQuote quote);
}
