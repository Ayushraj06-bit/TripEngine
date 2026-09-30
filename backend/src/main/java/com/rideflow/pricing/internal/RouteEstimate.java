package com.rideflow.pricing.internal;

/** How far and how long a trip is expected to be. */
record RouteEstimate(long distanceMeters, long durationSeconds) {}
