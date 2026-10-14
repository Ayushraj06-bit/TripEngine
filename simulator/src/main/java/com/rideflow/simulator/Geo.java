package com.rideflow.simulator;

import com.rideflow.simulator.SimulatorProperties.Bounds;
import com.rideflow.simulator.SimulatorProperties.Hotspot;
import java.util.List;
import java.util.random.RandomGenerator;

/** Spherical-earth geometry and random places. Pure, so it is unit tested without a backend. */
final class Geo {

  /** The mean earth radius, as the backend's own distance uses. */
  static final double EARTH_RADIUS_METERS = 6_371_008.8;

  private static final double METERS_PER_DEGREE_LAT = Math.PI * EARTH_RADIUS_METERS / 180;

  private Geo() {}

  record Point(double lat, double lng) {}

  /** Great-circle distance (haversine). */
  static double distanceMeters(Point a, Point b) {
    double dLat = Math.toRadians(b.lat() - a.lat());
    double dLng = Math.toRadians(b.lng() - a.lng());
    double h =
        Math.pow(Math.sin(dLat / 2), 2)
            + Math.cos(Math.toRadians(a.lat()))
                * Math.cos(Math.toRadians(b.lat()))
                * Math.pow(Math.sin(dLng / 2), 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  /** The initial compass heading from {@code from} to {@code to}: 0 is north, 90 east. */
  static double bearingDegrees(Point from, Point to) {
    double lat1 = Math.toRadians(from.lat());
    double lat2 = Math.toRadians(to.lat());
    double dLng = Math.toRadians(to.lng() - from.lng());
    double y = Math.sin(dLng) * Math.cos(lat2);
    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
  }

  /** The point {@code meters} away from {@code from} along {@code bearingDegrees}. */
  static Point offset(Point from, double bearingDegrees, double meters) {
    double angular = meters / EARTH_RADIUS_METERS;
    double bearing = Math.toRadians(bearingDegrees);
    double lat1 = Math.toRadians(from.lat());
    double lng1 = Math.toRadians(from.lng());
    double lat2 =
        Math.asin(
            Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
    double lng2 =
        lng1
            + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
    return new Point(Math.toDegrees(lat2), Math.toDegrees(lng2));
  }

  /** What a phone's GPS reports: the true position off by a normal error of {@code sigma} m. */
  static Point noisy(Point truth, double sigmaMeters, RandomGenerator random) {
    double north = random.nextGaussian() * sigmaMeters;
    double east = random.nextGaussian() * sigmaMeters;
    double metersPerDegreeLng = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(truth.lat()));
    return new Point(
        truth.lat() + north / METERS_PER_DEGREE_LAT, truth.lng() + east / metersPerDegreeLng);
  }

  static Point anywhereIn(Bounds bounds, RandomGenerator random) {
    return new Point(
        random.nextDouble(bounds.minLat(), bounds.maxLat()),
        random.nextDouble(bounds.minLng(), bounds.maxLng()));
  }

  /** A hotspot chosen by weight. */
  static Hotspot pick(List<Hotspot> hotspots, RandomGenerator random) {
    int total = hotspots.stream().mapToInt(Hotspot::weight).sum();
    int ticket = random.nextInt(total);
    for (Hotspot hotspot : hotspots) {
      ticket -= hotspot.weight();
      if (ticket < 0) {
        return hotspot;
      }
    }
    throw new IllegalStateException("weights changed while picking");
  }

  /** Uniformly over a disc of {@code radius} around {@code centre}, kept inside {@code bounds}. */
  static Point near(Point centre, double radiusMeters, Bounds bounds, RandomGenerator random) {
    Point point =
        offset(centre, random.nextDouble(360), radiusMeters * Math.sqrt(random.nextDouble()));
    return clamp(point, bounds);
  }

  static Point clamp(Point point, Bounds bounds) {
    return new Point(
        Math.clamp(point.lat(), bounds.minLat(), bounds.maxLat()),
        Math.clamp(point.lng(), bounds.minLng(), bounds.maxLng()));
  }
}
