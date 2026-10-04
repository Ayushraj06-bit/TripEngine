package com.rideflow.trip;

import com.rideflow.shared.GeoPoint;
import java.time.Instant;

/** One recorded position of a driver during a trip. */
public record RoutePoint(GeoPoint position, Instant at) {}
