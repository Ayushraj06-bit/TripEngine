package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** A driver was assigned. {@code driver} moves them to ON_TRIP. */
@Externalized(TripEvent.EXTERNALIZED)
public record DriverAssigned(
    UUID eventId, Instant occurredAt, UUID tripId, UUID riderId, UUID driverId)
    implements TripEvent {}
