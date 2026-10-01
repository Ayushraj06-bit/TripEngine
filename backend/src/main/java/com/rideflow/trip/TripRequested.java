package com.rideflow.trip;

import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** A rider booked a trip. Matching (Phase 5) starts looking for a driver. */
@Externalized(TripEvent.EXTERNALIZED)
public record TripRequested(
    UUID eventId,
    Instant occurredAt,
    UUID tripId,
    UUID riderId,
    VehicleType vehicleType,
    double pickupLat,
    double pickupLng,
    double dropLat,
    double dropLng,
    long quotedFarePaise,
    BigDecimal surgeMultiplier,
    PaymentMethod paymentMethod)
    implements TripEvent {}
