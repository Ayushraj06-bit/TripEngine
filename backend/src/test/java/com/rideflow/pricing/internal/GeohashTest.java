package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.shared.GeoPoint;
import org.junit.jupiter.api.Test;

class GeohashTest {

  /** The worked example from the geohash literature. */
  @Test
  void encodesAPointAsThePublishedReferenceDoes() {
    assertThat(Geohash.encode(new GeoPoint(57.64911, 10.40744), 11)).isEqualTo("u4pruydqqvj");
    assertThat(Geohash.encode(new GeoPoint(42.6, -5.6), 5)).isEqualTo("ezs42");
  }

  @Test
  void decodesACellToBoundsThatHoldThePoint() {
    GeoPoint mgRoad = new GeoPoint(12.9752, 77.6064);
    Geohash.Bounds cell = Geohash.bounds(Geohash.encode(mgRoad, 6));

    assertThat(mgRoad.latitude()).isBetween(cell.minLat(), cell.maxLat());
    assertThat(mgRoad.longitude()).isBetween(cell.minLng(), cell.maxLng());
    // Precision 6 near the equator: about 1.2 km by 0.6 km.
    assertThat(cell.widthMeters()).isCloseTo(1_190, within(20.0));
    assertThat(cell.heightMeters()).isCloseTo(611, within(5.0));
  }

  /** The reference neighbours of ezs42, clockwise from north. */
  @Test
  void findsTheEightCellsAround() {
    assertThat(Geohash.neighbours("ezs42"))
        .containsExactlyInAnyOrder(
            "ezs48", "ezs49", "ezs43", "ezs41", "ezs40", "ezefp", "ezefr", "ezefx");
    assertThat(Geohash.block("ezs42")).hasSize(9).first().isEqualTo("ezs42");
  }

  @Test
  void wrapsAcrossTheAntimeridianAndStopsAtThePoles() {
    String eastEdge = Geohash.encode(new GeoPoint(0.1, 179.99), 4);
    String westEdge = Geohash.encode(new GeoPoint(0.1, -179.99), 4);
    assertThat(Geohash.neighbours(eastEdge)).contains(westEdge);

    String northPole = Geohash.encode(new GeoPoint(89.99, 0.1), 4);
    assertThat(Geohash.neighbours(northPole)).hasSize(5);
  }

  @Test
  void acceptsOnlyCellsOfTheRightLengthAndAlphabet() {
    assertThat(Geohash.isValid("tdr1v9", 6)).isTrue();
    assertThat(Geohash.isValid("tdr1v", 6)).isFalse();
    assertThat(Geohash.isValid("tdr1va", 6)).isFalse();
    assertThat(Geohash.isValid(null, 6)).isFalse();
    assertThatThrownBy(() -> Geohash.bounds("tdr1va")).isInstanceOf(IllegalArgumentException.class);
  }
}
