package com.rideflow.trip.internal;

import com.rideflow.trip.RoutePoint;
import java.time.Duration;
import java.util.List;

/**
 * How far a recorded route really went. Pure: no clock, no Redis.
 *
 * <p>A point whose implied speed from the last good point exceeds {@code max-speed-kmph} is a GPS
 * jump - a reflection off a building, a fix from a cell tower - and is skipped; the next point is
 * measured from the last good one, so one bad fix costs nothing but itself. Without this, a single
 * jump across the city would put kilometres on the rider's fare.
 */
final class RouteDistance {

  private RouteDistance() {}

  static long meters(List<RoutePoint> route, double maxSpeedKmph) {
    double total = 0;
    RoutePoint last = null;
    for (RoutePoint point : route) {
      if (last == null) {
        last = point;
        continue;
      }
      double seconds = Duration.between(last.at(), point.at()).toMillis() / 1000.0;
      if (seconds <= 0) {
        // A repeat, or a point recorded out of order: it adds no distance.
        continue;
      }
      double meters = last.position().distanceMetersTo(point.position());
      if (meters / seconds * 3.6 > maxSpeedKmph) {
        continue;
      }
      total += meters;
      last = point;
    }
    return Math.round(total);
  }
}
