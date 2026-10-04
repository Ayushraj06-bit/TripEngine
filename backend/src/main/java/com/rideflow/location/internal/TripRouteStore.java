package com.rideflow.location.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.RoutePoint;
import com.rideflow.trip.TripRoutes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@code trip:route:{tripId}}: each accepted position of a trip in progress, as {@code
 * "lat,lng,epochMs"}. Read by {@code trip} when the trip completes, to price what was driven.
 */
@Component
class TripRouteStore implements TripRoutes {

  private final StringRedisTemplate redis;
  private final RedisScript<Long> routeAppendScript;
  private final RideFlowProperties properties;

  TripRouteStore(
      StringRedisTemplate redis,
      RedisScript<Long> routeAppendScript,
      RideFlowProperties properties) {
    this.redis = redis;
    this.routeAppendScript = routeAppendScript;
    this.properties = properties;
  }

  void append(UUID tripId, GeoPoint position, Instant at) {
    var location = properties.location();
    redis.execute(
        routeAppendScript,
        List.of(LocationKeys.route(tripId)),
        position.latitude() + "," + position.longitude() + "," + at.toEpochMilli(),
        Integer.toString(location.routeMaxPoints()),
        Long.toString(Duration.ofHours(location.routeTtlHours()).toMillis()));
  }

  @Override
  public List<RoutePoint> route(UUID tripId) {
    List<String> points = redis.opsForList().range(LocationKeys.route(tripId), 0, -1);
    if (points == null) {
      return List.of();
    }
    return points.stream()
        .map(point -> point.split(","))
        .map(
            parts ->
                new RoutePoint(
                    new GeoPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])),
                    Instant.ofEpochMilli(Long.parseLong(parts[2]))))
        .toList();
  }

  @Override
  public void forget(UUID tripId) {
    redis.delete(LocationKeys.route(tripId));
  }
}
