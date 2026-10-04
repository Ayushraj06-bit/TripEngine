package com.rideflow.trip;

import java.util.List;
import java.util.UUID;

/**
 * The route a trip actually took, recorded while it was IN_PROGRESS.
 *
 * <p>Declared here and implemented by {@code location}, which records the points as positions
 * arrive - the same direction as {@link DriverPositions}, for the same reason: {@code trip}
 * depending on {@code location} would close a module cycle.
 */
public interface TripRoutes {

  /** The points in the order they were recorded; empty if none were. */
  List<RoutePoint> route(UUID tripId);

  /** Deletes the recorded route, once the final fare no longer needs it. */
  void forget(UUID tripId);
}
