package com.rideflow.simulator;

import com.rideflow.simulator.Geo.Point;
import java.time.Duration;

/**
 * A vehicle driving straight at a waypoint at a steady speed: no routing service, as the brief
 * asks. Owned by one driver and only ever touched from that driver's thread.
 */
final class Movement {

  private Point position;
  private Point waypoint;
  private double metersPerSecond;
  private double headingDegrees;

  Movement(Point start) {
    this.position = start;
    this.waypoint = start;
  }

  void headFor(Point next, double speedKmph) {
    this.waypoint = next;
    this.metersPerSecond = speedKmph / 3.6;
    if (!arrived()) {
      this.headingDegrees = Geo.bearingDegrees(position, next);
    }
  }

  /**
   * Drives for {@code elapsed}. Never overshoots: a step that would pass the waypoint ends on it.
   *
   * @return true once at the waypoint
   */
  boolean advance(Duration elapsed) {
    double remaining = Geo.distanceMeters(position, waypoint);
    double step = metersPerSecond * elapsed.toNanos() / 1e9;
    if (step >= remaining) {
      position = waypoint;
      return true;
    }
    headingDegrees = Geo.bearingDegrees(position, waypoint);
    position = Geo.offset(position, headingDegrees, step);
    return false;
  }

  boolean arrived() {
    return position.equals(waypoint);
  }

  Point position() {
    return position;
  }

  double headingDegrees() {
    return headingDegrees;
  }

  double speedKmph() {
    return arrived() ? 0 : metersPerSecond * 3.6;
  }
}
