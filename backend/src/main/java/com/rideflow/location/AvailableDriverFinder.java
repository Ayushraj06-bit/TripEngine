package com.rideflow.location;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import java.util.List;

/**
 * Finds drivers who are online, in the service area and free to take a trip.
 *
 * <p>The API {@code matching} uses from Phase 5. It reads Redis GEO rather than the database: a
 * driver's position changes every few seconds and would otherwise be roughly 670 writes a second
 * against a table that also has to serve the trip lifecycle.
 */
public interface AvailableDriverFinder {

  /**
   * Available drivers of {@code type} within {@code radiusKm} of {@code centre}, nearest first.
   *
   * <p>The radius is inclusive: a driver at exactly the boundary is returned. Redis {@code
   * GEOSEARCH} defines it that way and matching's widening rings rely on it - an exclusive bound
   * would leave a driver unreachable at every step.
   *
   * @param limit maximum results; matching uses {@code rideflow.matching.candidates-per-search}
   */
  List<AvailableDriver> findAvailableDrivers(
      GeoPoint centre, VehicleType type, double radiusKm, int limit);
}
