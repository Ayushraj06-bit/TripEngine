package com.rideflow.trip;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip that needs a driver.
 *
 * @param waitingSince when it last entered REQUESTED: the booking, or the moment its driver backed
 *     out. Matching's deadline runs from here, so a trip handed back late gets a full search rather
 *     than the end of its first one.
 */
public record WaitingTrip(
    UUID tripId,
    UUID riderId,
    VehicleType vehicleType,
    GeoPoint pickup,
    long quotedFarePaise,
    Instant waitingSince) {}
