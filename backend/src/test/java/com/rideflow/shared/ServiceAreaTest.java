package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServiceAreaTest {

  /** A box around Bengaluru, matching the shipped configuration. */
  private static final ServiceArea BOX =
      new ServiceArea(
          List.of(
              new GeoPoint(13.14, 77.46),
              new GeoPoint(13.14, 77.78),
              new GeoPoint(12.82, 77.78),
              new GeoPoint(12.82, 77.46)));

  @Nested
  class Construction {

    @Test
    void rejectsAPolygonWithTooFewVertices() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new ServiceArea(List.of(new GeoPoint(0, 0), new GeoPoint(1, 1))))
          .withMessageContaining("3 vertices");
    }

    @Test
    void keepsItsVerticesImmutable() {
      var mutable = new java.util.ArrayList<>(BOX.vertices());
      var area = new ServiceArea(mutable);
      mutable.clear();

      // A caller mutating the list afterwards must not silently redraw the city.
      assertThat(area.vertices()).hasSize(4);
    }
  }

  @Nested
  class Containment {

    @ParameterizedTest
    @CsvSource({
      "12.9752, 77.6064", // MG Road, comfortably inside
      "13.0000, 77.5000",
      "12.9000, 77.7000",
    })
    void acceptsPointsInside(double lat, double lng) {
      assertThat(BOX.contains(new GeoPoint(lat, lng))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
      "13.2000, 77.6000", // north of the box
      "12.7000, 77.6000", // south
      "12.9752, 77.3000", // west
      "12.9752, 77.9000", // east
      "19.0760, 72.8777", // Mumbai
    })
    void rejectsPointsOutside(double lat, double lng) {
      assertThat(BOX.contains(new GeoPoint(lat, lng))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
      "13.14, 77.60", // on the northern edge
      "12.82, 77.60", // southern edge
      "12.98, 77.46", // western edge
      "12.98, 77.78", // eastern edge
    })
    void treatsAPointOnAnEdgeAsInside(double lat, double lng) {
      // ADR-documented: a driver parked on the boundary should be able to work, and a
      // rider standing on it should be able to book. Asserted rather than left to
      // floating point, so a change to the algorithm cannot quietly shrink the city.
      assertThat(BOX.contains(new GeoPoint(lat, lng))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"13.14, 77.46", "13.14, 77.78", "12.82, 77.78", "12.82, 77.46"})
    void treatsAVertexAsInside(double lat, double lng) {
      assertThat(BOX.contains(new GeoPoint(lat, lng))).isTrue();
    }

    @Test
    void handlesAConcavePolygon() {
      // An L shape: ray casting has to cross four edges to get this right, which a
      // naive bounding-box check would not.
      ServiceArea lShape =
          new ServiceArea(
              List.of(
                  new GeoPoint(0, 0),
                  new GeoPoint(0, 4),
                  new GeoPoint(2, 4),
                  new GeoPoint(2, 2),
                  new GeoPoint(4, 2),
                  new GeoPoint(4, 0)));

      assertThat(lShape.contains(new GeoPoint(1, 1))).isTrue();
      assertThat(lShape.contains(new GeoPoint(3, 1))).isTrue();
      assertThat(lShape.contains(new GeoPoint(1, 3))).isTrue();
      // The notch: inside the bounding box, outside the shape.
      assertThat(lShape.contains(new GeoPoint(3, 3))).isFalse();
    }

    @Test
    void doesNotDependOnWindingDirection() {
      ServiceArea reversed = new ServiceArea(BOX.vertices().reversed());

      assertThat(reversed.contains(new GeoPoint(12.9752, 77.6064))).isTrue();
      assertThat(reversed.contains(new GeoPoint(19.0760, 72.8777))).isFalse();
    }
  }
}
