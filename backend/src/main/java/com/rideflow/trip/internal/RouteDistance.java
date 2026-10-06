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
 *
 * <p>The first point has nothing before it to be checked against, so it starts the route only once
 * one of the next two agrees with it. A bad first fix would otherwise make every good point after
 * it look like a jump, until enough time had passed to excuse the distance - which would then be
 * charged. Looking two ahead lets a good first point survive a jump straight after it.
 */
final class RouteDistance {

  private RouteDistance() {}

  static long meters(List<RoutePoint> route, double maxSpeedKmph) {
    double total = 0;
    RoutePoint last = null;
    for (int i = firstTrusted(route, maxSpeedKmph); i < route.size(); i++) {
      RoutePoint point = route.get(i);
      if (last == null) {
        last = point;
      } else if (plausible(last, point, maxSpeedKmph)) {
        total += last.position().distanceMetersTo(point.position());
        last = point;
      }
    }
    return Math.round(total);
  }

  /** The first point that one of the next two agrees with; the route's length if none. */
  private static int firstTrusted(List<RoutePoint> route, double maxSpeedKmph) {
    for (int i = 0; i < route.size(); i++) {
      for (int next = i + 1; next <= i + 2 && next < route.size(); next++) {
        if (plausible(route.get(i), route.get(next), maxSpeedKmph)) {
          return i;
        }
      }
    }
    return route.size();
  }

  /**
   * Whether a driver could have gone from one point to the other. A repeated time, or one out of
   * order, is not: it adds no distance.
   */
  private static boolean plausible(RoutePoint from, RoutePoint to, double maxSpeedKmph) {
    double seconds = Duration.between(from.at(), to.at()).toMillis() / 1000.0;
    return seconds > 0
        && from.position().distanceMetersTo(to.position()) / seconds * 3.6 <= maxSpeedKmph;
  }
}
