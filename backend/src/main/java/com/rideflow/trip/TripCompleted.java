package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** The trip finished. Payment (Phase 7) charges {@code finalFarePaise}. */
@Externalized(TripEvent.EXTERNALIZED)
public record TripCompleted(
    UUID eventId,
    Instant occurredAt,
    UUID tripId,
    UUID riderId,
    UUID driverId,
    long finalFarePaise,
    PaymentMethod paymentMethod)
    implements TripEvent {}
