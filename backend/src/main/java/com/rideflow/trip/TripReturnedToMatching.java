package com.rideflow.trip;

import com.rideflow.shared.VehicleType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * The assigned driver cancelled before arriving, so the trip needs a driver again.
 *
 * <p>Matching retries it with priority; {@code driver} frees the previous driver.
 */
@Externalized(TripEvent.EXTERNALIZED)
public record TripReturnedToMatching(
    UUID eventId,
    Instant occurredAt,
    UUID tripId,
    UUID riderId,
    UUID previousDriverId,
    VehicleType vehicleType,
    double pickupLat,
    double pickupLng)
    implements TripEvent {}
