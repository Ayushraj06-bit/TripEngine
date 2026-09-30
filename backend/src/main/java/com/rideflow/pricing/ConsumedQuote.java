package com.rideflow.pricing;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A quote that has been used, with everything a booking needs to record.
 *
 * <p>{@code rateCardId} is the snapshot PROJECT_SPEC section 7 asks trips to keep: the exact rates
 * this price came from, so a later rate change can never alter what a rider was quoted.
 */
public record ConsumedQuote(
    UUID quoteId,
    UUID riderId,
    VehicleType vehicleType,
    GeoPoint pickup,
    GeoPoint drop,
    UUID rateCardId,
    BigDecimal surgeMultiplier,
    long distanceMeters,
    long durationSeconds,
    FareBreakdown fare,
    Instant expiresAt) {}
