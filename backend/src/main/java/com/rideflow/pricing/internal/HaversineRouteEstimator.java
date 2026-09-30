package com.rideflow.pricing.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Straight-line distance stretched by a road factor, and duration from an average city speed.
 *
 * <p>Roads are never straight, so the great-circle distance is multiplied by {@code road-factor}
 * (1.3): a rough but honest correction that needs no external service and cannot fail. That last
 * property is why this stays as the fallback even once a real router exists.
 */
@Component
class HaversineRouteEstimator implements RouteEstimator {

  private final double roadFactor;
  private final RideFlowProperties.Pricing.AverageSpeedKmph speeds;

  @Autowired
  HaversineRouteEstimator(RideFlowProperties properties) {
    this(properties.pricing().roadFactor(), properties.pricing().averageSpeedKmph());
  }

  HaversineRouteEstimator(double roadFactor, RideFlowProperties.Pricing.AverageSpeedKmph speeds) {
    this.roadFactor = roadFactor;
    this.speeds = speeds;
  }

  @Override
  public RouteEstimate estimate(GeoPoint from, GeoPoint to, VehicleType type) {
    long distanceMeters = Math.round(from.distanceMetersTo(to) * roadFactor);
    return new RouteEstimate(distanceMeters, durationSeconds(distanceMeters, speeds.of(type)));
  }

  /**
   * Rounded up: an estimate that under-promises duration by a second is worse than one that
   * over-promises it.
   *
   * <p>Multiplied before dividing, so an exact answer stays exact. Dividing by a pre-computed
   * 6.111... m/s puts 11 km at 22 km/h a hair over 1800 s, and the ceiling makes that 1801.
   */
  static long durationSeconds(long distanceMeters, double speedKmph) {
    return (long) Math.ceil(distanceMeters * 3600.0 / (speedKmph * 1000.0));
  }
}
