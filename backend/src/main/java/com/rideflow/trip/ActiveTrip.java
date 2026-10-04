package com.rideflow.trip;

import com.rideflow.shared.GeoPoint;
import java.util.UUID;

/** The trip a driver is on right now: what {@code location} needs to show it to the rider. */
public record ActiveTrip(
    UUID tripId, UUID riderId, TripStatus status, GeoPoint pickup, GeoPoint drop) {}
