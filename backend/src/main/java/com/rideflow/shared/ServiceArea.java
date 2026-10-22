package com.rideflow.shared;

import java.util.List;
import java.util.Objects;

/**
 * The polygon RideFlow operates inside.
 *
 * <p>PROJECT_SPEC section 1 scopes the service to one configured city. Both {@code location} (a
 * driver outside the area must not be offered trips) and {@code pricing} (a pickup or drop outside
 * it cannot be quoted) need the same answer, so the polygon lives here rather than in either of
 * them.
 *
 * <p>Point-in-polygon by ray casting: count how many edges a ray from the point crosses, and an odd
 * count means inside. Deliberately not a geospatial library - this is one convex-ish city boundary
 * tested against a handful of points per request, and a dependency would buy nothing.
 *
 * <p>The polygon is treated as closed: the last vertex joins the first, so the configuration does
 * not have to repeat it.
 *
 * <p><strong>A point exactly on an edge counts as inside.</strong> Floating point makes "exactly on
 * the edge" largely theoretical, but the rule has to be decided rather than discovered: a driver
 * parked on the boundary should be able to work, and a rider standing on it should be able to book.
 *
 * <p>Not final: Spring Modulith's observability wraps every module's API beans in a proxy, to give
 * each call across modules its own span.
 */
public class ServiceArea {

  private final List<GeoPoint> vertices;

  /**
   * @param vertices at least three, in order around the boundary. Winding direction does not
   *     matter.
   */
  public ServiceArea(List<GeoPoint> vertices) {
    Objects.requireNonNull(vertices, "vertices");
    if (vertices.size() < 3) {
      throw new IllegalArgumentException(
          "a service area needs at least 3 vertices, got " + vertices.size());
    }
    this.vertices = List.copyOf(vertices);
  }

  public List<GeoPoint> vertices() {
    return vertices;
  }

  /** Whether {@code point} lies inside the area, edges included. */
  public boolean contains(GeoPoint point) {
    Objects.requireNonNull(point, "point");

    if (isOnAnyEdge(point)) {
      return true;
    }

    boolean inside = false;
    for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
      GeoPoint a = vertices.get(i);
      GeoPoint b = vertices.get(j);

      // Does the horizontal ray from the point cross this edge?
      boolean straddlesRay = (a.latitude() > point.latitude()) != (b.latitude() > point.latitude());
      if (!straddlesRay) {
        continue;
      }
      double crossingLongitude =
          (b.longitude() - a.longitude())
                  * (point.latitude() - a.latitude())
                  / (b.latitude() - a.latitude())
              + a.longitude();
      if (point.longitude() < crossingLongitude) {
        inside = !inside;
      }
    }
    return inside;
  }

  private boolean isOnAnyEdge(GeoPoint point) {
    for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
      if (isOnSegment(point, vertices.get(j), vertices.get(i))) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code point} lies on the segment from {@code a} to {@code b}, within a tolerance. */
  private static boolean isOnSegment(GeoPoint point, GeoPoint a, GeoPoint b) {
    // About a centimetre in degrees: far below GPS accuracy, so this only catches
    // points that are genuinely on the line rather than merely near it.
    final double tolerance = 1e-9;

    double cross =
        (point.latitude() - a.latitude()) * (b.longitude() - a.longitude())
            - (point.longitude() - a.longitude()) * (b.latitude() - a.latitude());
    if (Math.abs(cross) > tolerance) {
      return false;
    }
    // Collinear: now check it falls between the endpoints rather than beyond them.
    return within(point.latitude(), a.latitude(), b.latitude())
        && within(point.longitude(), a.longitude(), b.longitude());
  }

  private static boolean within(double value, double bound1, double bound2) {
    return value >= Math.min(bound1, bound2) - 1e-9 && value <= Math.max(bound1, bound2) + 1e-9;
  }
}
