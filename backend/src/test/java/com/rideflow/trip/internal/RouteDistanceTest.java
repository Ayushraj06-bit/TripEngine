package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.shared.GeoPoint;
import com.rideflow.trip.RoutePoint;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteDistanceTest {

  private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
  private static final GeoPoint START = new GeoPoint(12.9752, 77.6064);

  /** The section 9 speed limit for a plausible fix. */
  private static final double MAX_KMPH = 150;

  private static RoutePoint at(double metersNorth, long seconds) {
    return new RoutePoint(
        new GeoPoint(
            START.latitude() + Math.toDegrees(metersNorth / 6_371_008.8), START.longitude()),
        T0.plusSeconds(seconds));
  }

  @Test
  void addsUpTheLegs() {
    assertThat(RouteDistance.meters(List.of(at(0, 0), at(200, 20), at(500, 50)), MAX_KMPH))
        .isCloseTo(500, within(1L));
  }

  @Test
  void skipsAJumpAndMeasuresTheNextPointFromTheLastGoodOne() {
    // 3 km in 5 s is 2,160 km/h: a bad fix, not a drive.
    List<RoutePoint> route = List.of(at(0, 0), at(3_000, 5), at(100, 10), at(200, 20));

    assertThat(RouteDistance.meters(route, MAX_KMPH)).isCloseTo(200, within(1L));
  }

  @Test
  void ignoresARepeatedTimestamp() {
    assertThat(RouteDistance.meters(List.of(at(0, 0), at(100, 10), at(100, 10)), MAX_KMPH))
        .isCloseTo(100, within(1L));
  }

  @Test
  void isZeroForOnePointOrNone() {
    assertThat(RouteDistance.meters(List.of(at(0, 0)), MAX_KMPH)).isZero();
    assertThat(RouteDistance.meters(List.of(), MAX_KMPH)).isZero();
  }
}
