package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** The driver reached the pickup point. */
@Externalized(TripEvent.EXTERNALIZED)
public record DriverArrived(
    UUID eventId, Instant occurredAt, UUID tripId, UUID riderId, UUID driverId)
    implements TripEvent {}
