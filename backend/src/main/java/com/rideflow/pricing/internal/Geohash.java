package com.rideflow.pricing.internal;

import com.rideflow.shared.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Geohash cells (PROJECT_SPEC section 8: surge is priced per cell). Pure: no Redis, no clock.
 *
 * <p>A geohash halves the world alternately by longitude and latitude, five halvings to a base-32
 * character, so a longer hash is a smaller cell. At precision 6 a cell is about 1.2 km wide and 0.6
 * km tall: a neighbourhood. A library would add a dependency for some sixty lines.
 */
final class Geohash {

  private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

  /** Metres per degree of latitude: GeoPoint's mean Earth radius, in degrees. */
  private static final double METERS_PER_DEGREE = Math.toRadians(6_371_008.8);

  private Geohash() {}

  /** A cell's edges, in degrees. */
  record Bounds(double minLat, double maxLat, double minLng, double maxLng) {

    GeoPoint center() {
      return new GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2);
    }

    double heightDegrees() {
      return maxLat - minLat;
    }

    double widthDegrees() {
      return maxLng - minLng;
    }

    double heightMeters() {
      return heightDegrees() * METERS_PER_DEGREE;
    }

    /** At the cell's middle latitude: a degree of longitude shrinks towards the poles. */
    double widthMeters() {
      return widthDegrees() * METERS_PER_DEGREE * Math.cos(Math.toRadians(center().latitude()));
    }
  }

  static String encode(GeoPoint point, int precision) {
    double minLat = -90;
    double maxLat = 90;
    double minLng = -180;
    double maxLng = 180;
    StringBuilder hash = new StringBuilder(precision);
    boolean longitude = true;
    int bits = 0;
    int value = 0;
    while (hash.length() < precision) {
      if (longitude) {
        double mid = (minLng + maxLng) / 2;
        if (point.longitude() >= mid) {
          value = value * 2 + 1;
          minLng = mid;
        } else {
          value = value * 2;
          maxLng = mid;
        }
      } else {
        double mid = (minLat + maxLat) / 2;
        if (point.latitude() >= mid) {
          value = value * 2 + 1;
          minLat = mid;
        } else {
          value = value * 2;
          maxLat = mid;
        }
      }
      longitude = !longitude;
      if (++bits == 5) {
        hash.append(BASE32.charAt(value));
        bits = 0;
        value = 0;
      }
    }
    return hash.toString();
  }

  /**
   * @throws IllegalArgumentException for a character outside the geohash alphabet
   */
  static Bounds bounds(String hash) {
    double minLat = -90;
    double maxLat = 90;
    double minLng = -180;
    double maxLng = 180;
    boolean longitude = true;
    for (char c : hash.toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) {
        throw new IllegalArgumentException("Not a geohash: " + hash);
      }
      for (int bit = 4; bit >= 0; bit--) {
        boolean high = ((value >> bit) & 1) == 1;
        if (longitude) {
          double mid = (minLng + maxLng) / 2;
          if (high) {
            minLng = mid;
          } else {
            maxLng = mid;
          }
        } else {
          double mid = (minLat + maxLat) / 2;
          if (high) {
            minLat = mid;
          } else {
            maxLat = mid;
          }
        }
        longitude = !longitude;
      }
    }
    return new Bounds(minLat, maxLat, minLng, maxLng);
  }

  /** Whether {@code candidate} is a cell of exactly this precision. */
  static boolean isValid(String candidate, int precision) {
    return candidate != null
        && candidate.length() == precision
        && candidate.chars().allMatch(c -> BASE32.indexOf(c) >= 0);
  }

  /**
   * The eight cells around this one, found by stepping one cell width or height from its centre and
   * encoding where that lands. None past a pole; across the antimeridian, the other side.
   */
  static List<String> neighbours(String hash) {
    Bounds cell = bounds(hash);
    GeoPoint center = cell.center();
    List<String> around = new ArrayList<>(8);
    for (int dLat = -1; dLat <= 1; dLat++) {
      for (int dLng = -1; dLng <= 1; dLng++) {
        if (dLat == 0 && dLng == 0) {
          continue;
        }
        double lat = center.latitude() + dLat * cell.heightDegrees();
        if (lat <= -90 || lat >= 90) {
          continue;
        }
        double lng = center.longitude() + dLng * cell.widthDegrees();
        lng = lng > 180 ? lng - 360 : lng < -180 ? lng + 360 : lng;
        around.add(encode(new GeoPoint(lat, lng), hash.length()));
      }
    }
    return around;
  }

  /** This cell and its eight neighbours: the block whose demand a cell's surge is priced on. */
  static List<String> block(String hash) {
    List<String> block = new ArrayList<>(9);
    block.add(hash);
    block.addAll(neighbours(hash));
    return block;
  }
}
