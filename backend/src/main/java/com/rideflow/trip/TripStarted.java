package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** The rider gave the driver the right OTP and the trip began. */
@Externalized(TripEvent.EXTERNALIZED)
public record TripStarted(
    UUID eventId, Instant occurredAt, UUID tripId, UUID riderId, UUID driverId)
    implements TripEvent {}
