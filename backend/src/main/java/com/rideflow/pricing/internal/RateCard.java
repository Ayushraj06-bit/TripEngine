package com.rideflow.pricing.internal;

import com.rideflow.shared.Money;
import com.rideflow.shared.VehicleType;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of the rates for one vehicle type.
 *
 * <p>A plain value, not the JPA entity: the fare calculator works on this, so it needs no database,
 * no Spring and no persistence context, and the in-memory cache never holds managed entities.
 */
record RateCard(
    UUID id,
    VehicleType vehicleType,
    int version,
    Money base,
    Money perKm,
    Money perMinute,
    Money minimumFare,
    Money waitingPerMinute,
    Money cancellationFee,
    Instant effectiveFrom) {}
