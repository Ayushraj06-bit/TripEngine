package com.rideflow.location;

import java.util.UUID;

/**
 * A driver who can be offered a trip, and how far away they are.
 *
 * @param distanceMeters straight-line distance from the search point, as Redis GEO measures it.
 *     Road distance is longer; matching ranks on this and lets pricing worry about the difference.
 */
public record AvailableDriver(UUID driverId, double distanceMeters) {}
