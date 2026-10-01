package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A trip was cancelled for good.
 *
 * @param driverId null when no driver had been assigned
 * @param cancellationFeePaise what the rider owes for it; zero when free
 */
@Externalized(TripEvent.EXTERNALIZED)
public record TripCancelled(
    UUID eventId,
    Instant occurredAt,
    UUID tripId,
    UUID riderId,
    UUID driverId,
    TripActor cancelledBy,
    TripStatus previousStatus,
    long cancellationFeePaise,
    PaymentMethod paymentMethod)
    implements TripEvent {}
