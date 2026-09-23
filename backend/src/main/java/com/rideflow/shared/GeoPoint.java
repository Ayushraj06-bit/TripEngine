package com.rideflow.shared;

/**
 * A point on the earth, in decimal degrees (WGS 84).
 *
 * <p>PROJECT_SPEC section 10 requires coordinates to be validated at the edge; validating in the
 * constructor means an invalid {@code GeoPoint} cannot exist at all, so no code downstream has to
 * re-check. NaN and infinity are rejected explicitly, because they pass every range comparison
 * silently and would otherwise poison distance arithmetic.
 *
 * @param latitude degrees north, in [-90, 90]
 * @param longitude degrees east, in [-180, 180]
 */
public record GeoPoint(double latitude, double longitude) {

  /**
   * Mean earth radius in metres (IUGG). Distances are computed on a sphere: over the few kilometres
   * a city-scale search covers, the error against a full ellipsoidal calculation is well under the
   * accuracy of a phone's GPS fix.
   */
  private static final double EARTH_RADIUS_METERS = 6_371_008.8;

  public GeoPoint {
    if (!Double.isFinite(latitude)) {
      throw new IllegalArgumentException("latitude must be a finite number, was " + latitude);
    }
    if (!Double.isFinite(longitude)) {
      throw new IllegalArgumentException("longitude must be a finite number, was " + longitude);
    }
    if (latitude < -90.0 || latitude > 90.0) {
      throw new IllegalArgumentException("latitude must be in [-90, 90], was " + latitude);
    }
    if (longitude < -180.0 || longitude > 180.0) {
      throw new IllegalArgumentException("longitude must be in [-180, 180], was " + longitude);
    }
  }

  /**
   * Great-circle distance from this point to {@code other}, in metres.
   *
   * <p>Uses the haversine formula, which stays numerically stable for the short distances RideFlow
   * deals with, where the simpler spherical law of cosines loses precision.
   */
  public double distanceMetersTo(GeoPoint other) {
    if (this.equals(other)) {
      return 0.0;
    }
    double lat1 = Math.toRadians(latitude);
    double lat2 = Math.toRadians(other.latitude);
    double deltaLat = Math.toRadians(other.latitude - latitude);
    double deltaLon = Math.toRadians(other.longitude - longitude);

    double sinHalfLat = Math.sin(deltaLat / 2);
    double sinHalfLon = Math.sin(deltaLon / 2);
    double a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
    // min(1.0, ...) guards against a rounding error above 1 for antipodal points.
    double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    return EARTH_RADIUS_METERS * c;
  }

  /** Whether {@code other} lies within {@code radiusMeters} of this point, inclusive. */
  public boolean isWithin(double radiusMeters, GeoPoint other) {
    if (!Double.isFinite(radiusMeters) || radiusMeters < 0) {
      throw new IllegalArgumentException(
          "radiusMeters must be a finite, non-negative number, was " + radiusMeters);
    }
    return distanceMetersTo(other) <= radiusMeters;
  }
}
