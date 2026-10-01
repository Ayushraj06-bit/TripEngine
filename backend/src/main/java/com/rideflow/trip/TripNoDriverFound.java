package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** Matching gave up: no driver accepted before the deadline. */
@Externalized(TripEvent.EXTERNALIZED)
public record TripNoDriverFound(UUID eventId, Instant occurredAt, UUID tripId, UUID riderId)
    implements TripEvent {}
