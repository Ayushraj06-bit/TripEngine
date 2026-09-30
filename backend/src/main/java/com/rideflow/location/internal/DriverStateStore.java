package com.rideflow.location.internal;

import com.rideflow.location.AvailableDriver;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/** Everything this module keeps in Redis. */
@Component
class DriverStateStore {

  /**
   * PROJECT_SPEC section 7: the state hash lives 24 hours, refreshed on every write. Long enough
   * that a driver's shift never outlives it, short enough that someone who stops driving
   * disappears.
   */
  private static final Duration STATE_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;
  private final RedisScript<List> locationUpdateScript;
  private final RedisScript<List> staleDriversScript;
  private final RideFlowProperties properties;
  private final Clock clock;

  DriverStateStore(
      StringRedisTemplate redis,
      RedisScript<List> locationUpdateScript,
      RedisScript<List> staleDriversScript,
      RideFlowProperties properties,
      Clock clock) {
    this.redis = redis;
    this.locationUpdateScript = locationUpdateScript;
    this.staleDriversScript = staleDriversScript;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Applies one position update.
   *
   * <p>The script decides the outcome, because the checks it makes all read state it is about to
   * overwrite. See {@code location_update.lua}.
   */
  UpdateOutcome applyUpdate(UUID driverId, GeoPoint position, long seq, boolean inServiceArea) {
    var location = properties.location();

    List<String> keys = new ArrayList<>();
    keys.add(LocationKeys.state(driverId));
    keys.add(LocationKeys.LAST_SEEN);
    keys.addAll(LocationKeys.allAvailable());

    @SuppressWarnings("unchecked")
    List<Object> result =
        redis.execute(
            locationUpdateScript,
            keys,
            driverId.toString(),
            Double.toString(position.latitude()),
            Double.toString(position.longitude()),
            Long.toString(seq),
            // From the injected Clock, never Redis TIME: PROJECT_SPEC section 11, and
            // it is what lets a test advance time rather than wait.
            Long.toString(clock.millis()),
            Long.toString(location.minIntervalMs()),
            Double.toString(location.minMoveMeters()),
            Integer.toString(location.forceWriteSeconds()),
            Double.toString(location.maxSpeedKmph()),
            inServiceArea ? "1" : "0",
            Long.toString(STATE_TTL.toSeconds()));

    return UpdateOutcome.from(result);
  }

  /**
   * Records a status or vehicle-type change from the driver module.
   *
   * <p>Removes the driver from the previous type's set before adding them to the new one; without
   * that a driver who swapped a car for a bike would stay listed as a car and be offered the wrong
   * trips until their next position happened to be written.
   */
  void applyStatusChange(
      UUID driverId, String status, VehicleType vehicleType, VehicleType previousVehicleType) {

    String stateKey = LocationKeys.state(driverId);
    redis.opsForHash().put(stateKey, "status", status);
    if (vehicleType != null) {
      redis.opsForHash().put(stateKey, "vehicleType", vehicleType.name());
    }
    redis.expire(stateKey, STATE_TTL);

    boolean available = "AVAILABLE".equals(status);
    if (!available) {
      LocationKeys.allAvailable()
          .forEach(key -> redis.opsForZSet().remove(key, driverId.toString()));
      return;
    }

    if (previousVehicleType != null && previousVehicleType != vehicleType) {
      redis.opsForZSet().remove(LocationKeys.available(previousVehicleType), driverId.toString());
    }
    // The driver is added to the new set by their next position update, which is the
    // only thing that knows where they are. Going online without a fix leaves them
    // absent from the map, which is correct: nobody should be offered a trip from an
    // unknown location.
  }

  /** Removes drivers quiet for longer than {@code stale-after-seconds}, in one bounded batch. */
  List<StaleDriver> sweepStale() {
    var location = properties.location();
    long cutoffMs = clock.millis() - Duration.ofSeconds(location.staleAfterSeconds()).toMillis();

    List<String> keys = new ArrayList<>();
    keys.add(LocationKeys.LAST_SEEN);
    keys.addAll(LocationKeys.allAvailable());

    @SuppressWarnings("unchecked")
    List<Object> flat =
        redis.execute(
            staleDriversScript,
            keys,
            Long.toString(cutoffMs),
            Integer.toString(location.staleSweepBatchSize()));

    if (flat == null || flat.isEmpty()) {
      return List.of();
    }
    List<StaleDriver> stale = new ArrayList<>(flat.size() / 2);
    for (int i = 0; i + 1 < flat.size(); i += 2) {
      stale.add(
          new StaleDriver(
              UUID.fromString(String.valueOf(flat.get(i))),
              (long) Double.parseDouble(String.valueOf(flat.get(i + 1)))));
    }
    return stale;
  }

  /** Available drivers of one type within a radius, nearest first. The radius is inclusive. */
  List<AvailableDriver> search(GeoPoint centre, VehicleType type, double radiusKm, int limit) {
    var args =
        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
            .includeDistance()
            .sortAscending()
            .limit(limit);

    var results =
        redis
            .opsForGeo()
            .search(
                LocationKeys.available(type),
                GeoReference.fromCoordinate(new Point(centre.longitude(), centre.latitude())),
                new Distance(radiusKm, Metrics.KILOMETERS),
                args);

    if (results == null) {
      return List.of();
    }
    return results.getContent().stream()
        .map(
            entry ->
                new AvailableDriver(
                    UUID.fromString(entry.getContent().getName()),
                    // Explicitly metres. Metrics.NEUTRAL looks like "no conversion" but
                    // divides by the earth's radius, which made this a value near
                    // radians labelled metres - still correctly ordered, so nothing but
                    // a test asserting the number itself would ever have noticed.
                    entry.getDistance().in(RedisGeoCommands.DistanceUnit.METERS).getValue()))
        .toList();
  }

  /**
   * Positions of available drivers near a point, as the GEO set stores them.
   *
   * <p>{@code WITHCOORD} returns the coordinates in the same round trip. The stored GEO position
   * can trail the state hash by up to {@code force-write-seconds}, which is irrelevant for a view
   * that blurs everything to roughly 110 metres anyway.
   */
  List<GeoPoint> nearbyPositions(GeoPoint centre, VehicleType type, double radiusKm, int limit) {
    var args =
        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
            .includeCoordinates()
            .sortAscending()
            .limit(limit);
    var results =
        redis
            .opsForGeo()
            .search(
                LocationKeys.available(type),
                GeoReference.fromCoordinate(new Point(centre.longitude(), centre.latitude())),
                new Distance(radiusKm, Metrics.KILOMETERS),
                args);
    if (results == null) {
      return List.of();
    }
    return results.getContent().stream()
        .map(entry -> entry.getContent().getPoint())
        // Redis GEO is longitude first.
        .map(point -> new GeoPoint(point.getY(), point.getX()))
        .toList();
  }

  /** The stored state of one driver, for diagnostics and the arrival geofence. */
  Optional<GeoPoint> lastKnownPosition(UUID driverId) {
    Map<Object, Object> state = redis.opsForHash().entries(LocationKeys.state(driverId));
    Object lat = state.get("lat");
    Object lng = state.get("lng");
    if (lat == null || lng == null) {
      return Optional.empty();
    }
    return Optional.of(
        new GeoPoint(
            Double.parseDouble(String.valueOf(lat)), Double.parseDouble(String.valueOf(lng))));
  }

  /** Number of drivers currently listed as available for a type, for the gauge. */
  long availableCount(VehicleType type) {
    Long size = redis.opsForZSet().size(LocationKeys.available(type));
    return size == null ? 0 : size;
  }

  record StaleDriver(UUID driverId, long lastSeenMs) {}

  /** What the update script decided, and what it did. */
  record UpdateOutcome(String result, int spoofStrikes, boolean geoWritten) {

    static UpdateOutcome from(List<Object> raw) {
      if (raw == null || raw.size() < 3) {
        throw new IllegalStateException("location_update.lua returned an unexpected shape: " + raw);
      }
      return new UpdateOutcome(
          String.valueOf(raw.get(0)),
          Integer.parseInt(String.valueOf(raw.get(1))),
          "1".equals(String.valueOf(raw.get(2))));
    }

    boolean accepted() {
      return "accepted".equals(result);
    }
  }
}
