package com.rideflow.pricing.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;

/**
 * Estimates a trip's distance and duration.
 *
 * <p>An interface so a road-routing implementation (OSRM) can replace the straight-line estimate
 * later, behind a Resilience4j circuit breaker that falls back to this one. ADR 0007 records the
 * design; only the Haversine version exists today.
 *
 * <p>Takes the vehicle type because duration depends on it, and a real router would route a
 * motorbike and a car differently.
 */
interface RouteEstimator {

  RouteEstimate estimate(GeoPoint from, GeoPoint to, VehicleType type);
}
