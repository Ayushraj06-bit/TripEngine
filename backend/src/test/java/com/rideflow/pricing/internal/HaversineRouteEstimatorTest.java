package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties.Pricing.AverageSpeedKmph;
import com.rideflow.shared.VehicleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HaversineRouteEstimatorTest {

  private final HaversineRouteEstimator estimator =
      new HaversineRouteEstimator(1.3, new AverageSpeedKmph(25, 20, 22));

  @Test
  void stretchesTheStraightLineByTheRoadFactor() {
    // One degree of latitude is about 111.195 km; by road, 1.3 times that.
    RouteEstimate route =
        estimator.estimate(new GeoPoint(0, 0), new GeoPoint(1, 0), VehicleType.CAR);

    assertThat(route.distanceMeters()).isCloseTo(Math.round(111_195.08 * 1.3), within(1L));
  }

  @Test
  void estimatesNothingForTheSamePoint() {
    GeoPoint point = new GeoPoint(12.9752, 77.6064);

    assertThat(estimator.estimate(point, point, VehicleType.BIKE))
        .isEqualTo(new RouteEstimate(0, 0));
  }

  @Test
  void takesLongerInASlowerVehicle() {
    GeoPoint mgRoad = new GeoPoint(12.9752, 77.6064);
    GeoPoint indiranagar = new GeoPoint(12.9784, 77.6408);

    long bike = estimator.estimate(mgRoad, indiranagar, VehicleType.BIKE).durationSeconds();
    long auto = estimator.estimate(mgRoad, indiranagar, VehicleType.AUTO).durationSeconds();

    // 25 km/h against 20 km/h.
    assertThat(bike).isLessThan(auto);
  }

  @ParameterizedTest(name = "{0} m at {1} km/h is {2} s")
  @CsvSource({
    // Exact: the result must not pick up a stray second from floating point.
    "11000, 22, 1800",
    "5000, 20, 900",
    "12500, 25, 1800",
    // Not exact: rounded up, never down.
    "1, 22, 1",
    "1001, 22, 164",
    "0, 22, 0"
  })
  void convertsDistanceToDurationRoundingUp(long meters, double speedKmph, long seconds) {
    assertThat(HaversineRouteEstimator.durationSeconds(meters, speedKmph)).isEqualTo(seconds);
  }
}
