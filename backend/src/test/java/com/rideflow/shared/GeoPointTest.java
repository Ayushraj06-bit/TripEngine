package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GeoPointTest {

  /** Roughly MG Road, Bengaluru. */
  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);

  /** Roughly Indiranagar, Bengaluru, about 3 km east-north-east of MG Road. */
  private static final GeoPoint INDIRANAGAR = new GeoPoint(12.9784, 77.6408);

  @Nested
  class Validation {

    @ParameterizedTest
    @ValueSource(doubles = {-90.0, -45.5, 0.0, 12.9752, 90.0})
    void acceptsLatitudesInRange(double latitude) {
      assertThat(new GeoPoint(latitude, 0).latitude()).isEqualTo(latitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0, -0.1, 0.0, 77.6064, 180.0})
    void acceptsLongitudesInRange(double longitude) {
      assertThat(new GeoPoint(0, longitude).longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.0000001, 90.0000001, -90.0001, 90.0001, 91, -1000})
    void rejectsLatitudesOutOfRange(double latitude) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new GeoPoint(latitude, 0))
          .withMessageContaining("latitude");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0000001, 180.0000001, -180.0001, 180.0001, 360})
    void rejectsLongitudesOutOfRange(double longitude) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new GeoPoint(0, longitude))
          .withMessageContaining("longitude");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteLatitude(double latitude) {
      // NaN passes every range comparison silently, so it has to be rejected explicitly.
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new GeoPoint(latitude, 0))
          .withMessageContaining("finite");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteLongitude(double longitude) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new GeoPoint(0, longitude))
          .withMessageContaining("finite");
    }
  }

  @Nested
  class Distance {

    @Test
    void isZeroForTheSamePoint() {
      assertThat(MG_ROAD.distanceMetersTo(MG_ROAD)).isZero();
    }

    @Test
    void isZeroForAnEqualPoint() {
      assertThat(MG_ROAD.distanceMetersTo(new GeoPoint(12.9752, 77.6064))).isZero();
    }

    @Test
    void matchesOneDegreeOfLatitude() {
      // One degree of latitude is a fixed arc: R * pi / 180 = 111194.93 m.
      double oneDegree = new GeoPoint(0, 0).distanceMetersTo(new GeoPoint(1, 0));
      assertThat(oneDegree).isCloseTo(111_194.93, within(1.0));
    }

    @Test
    void shrinksWithLongitudeAwayFromTheEquator() {
      // A degree of longitude narrows by cos(latitude): at 60 degrees it is half
      // the width it has at the equator.
      double atEquator = new GeoPoint(0, 0).distanceMetersTo(new GeoPoint(0, 1));
      double atSixtyDegrees = new GeoPoint(60, 0).distanceMetersTo(new GeoPoint(60, 1));
      assertThat(atSixtyDegrees).isCloseTo(atEquator / 2, within(10.0));
    }

    @Test
    void measuresAShortCityDistance() {
      assertThat(MG_ROAD.distanceMetersTo(INDIRANAGAR)).isCloseTo(3_740.0, within(50.0));
    }

    @Test
    void isSymmetric() {
      assertThat(MG_ROAD.distanceMetersTo(INDIRANAGAR))
          .isCloseTo(INDIRANAGAR.distanceMetersTo(MG_ROAD), within(1e-6));
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 0, 180", "90, 0, -90, 0"})
    void staysFiniteForAntipodalPoints(double lat1, double lon1, double lat2, double lon2) {
      // sqrt(a) can exceed 1 by a rounding error here, which would make asin return NaN.
      double distance = new GeoPoint(lat1, lon1).distanceMetersTo(new GeoPoint(lat2, lon2));
      assertThat(distance).isFinite().isCloseTo(20_015_086.0, within(1_000.0));
    }
  }

  @Nested
  class WithinRadius {

    @Test
    void includesAPointInsideTheRadius() {
      // PROJECT_SPEC section 9 puts the arrival geofence at 150 m.
      GeoPoint nearby = new GeoPoint(12.9753, 77.6065);
      assertThat(MG_ROAD.isWithin(150, nearby)).isTrue();
    }

    @Test
    void excludesAPointOutsideTheRadius() {
      assertThat(MG_ROAD.isWithin(150, INDIRANAGAR)).isFalse();
    }

    @Test
    void treatsTheBoundaryAsInside() {
      assertThat(MG_ROAD.isWithin(0, MG_ROAD)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsAnUnusableRadius(double radius) {
      assertThatIllegalArgumentException().isThrownBy(() -> MG_ROAD.isWithin(radius, INDIRANAGAR));
    }
  }
}
