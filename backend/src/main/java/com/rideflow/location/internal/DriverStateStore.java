package com.rideflow.location.internal;

import com.rideflow.location.AvailableDriver;
import com.rideflow.location.LiveDrivers;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.DriverPositions;
import com.rideflow.trip.TripStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * Everything this module keeps in Redis.
 *
 * <p>Also answers {@code trip}'s {@link DriverPositions} port, for the arrival geofence, and the
 * admin live map's {@link LiveDrivers}.
 */
@Component
class DriverStateStore implements DriverPositions, LiveDrivers {

  /**
   * PROJECT_SPEC section 7: the state hash lives 24 hours, refreshed on every write. Long enough
   * that a driver's shift never outlives it, short enough that someone who stops driving
   * disappears.
   */
  private static final Duration STATE_TTL = Duration.ofHours(24);

  /** The state-hash fields that say which trip a driver is on. */
  private static final List<Object> TRIP_FIELDS =
      List.of("tripId", "riderId", "tripPhase", "targetLat", "targetLng", "lastForwardTs");

  /** What the live map shows of a driver, in the order {@link #liveDrivers()} reads it. */
  private static final byte[][] LIVE_FIELDS =
      Stream.of("status", "vehicleType", "lat", "lng", "tripPhase", "targetLat", "targetLng")
          .map(field -> field.getBytes(StandardCharsets.UTF_8))
          .toArray(byte[][]::new);

  private final StringRedisTemplate redis;
  private final RedisScript<List> locationUpdateScript;
  private final RedisScript<List> staleDriversScript;
  private final RedisScript<Long> tripContextScript;
  private final RedisScript<Long> driverStatusScript;
  private final RideFlowProperties properties;
  private final Clock clock;

  DriverStateStore(
      StringRedisTemplate redis,
      RedisScript<List> locationUpdateScript,
      RedisScript<List> staleDriversScript,
      RedisScript<Long> tripContextScript,
      RedisScript<Long> driverStatusScript,
      RideFlowProperties properties,
      Clock clock) {
    this.redis = redis;
    this.locationUpdateScript = locationUpdateScript;
    this.staleDriversScript = staleDriversScript;
    this.tripContextScript = tripContextScript;
    this.driverStatusScript = driverStatusScript;
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
            Long.toString(STATE_TTL.toSeconds()),
            Long.toString(location.driverLocationForwardMs()));

    return UpdateOutcome.from(result);
  }

  /**
   * Records a status or vehicle-type change from the driver module, unless a later one already has
   * been: changes for one driver can be handled in either order, and an ON_TRIP applied after the
   * AVAILABLE that followed it would hide an idle driver from matching. See {@code
   * driver_status.lua}.
   *
   * <p>Takes the driver out of every available set but the new vehicle type's: a driver who swapped
   * a car for a bike is not offered car trips. They join the new type's set with their next
   * position, the only thing that knows where they are.
   *
   * @param driverVersion the driver row's version after the change
   */
  void applyStatusChange(
      UUID driverId, String status, VehicleType vehicleType, long driverVersion) {
    List<String> keys = new ArrayList<>();
    keys.add(LocationKeys.state(driverId));
    keys.addAll(LocationKeys.allAvailable());
    redis.execute(
        driverStatusScript,
        keys,
        status,
        vehicleType == null ? "" : vehicleType.name(),
        Long.toString(driverVersion),
        Long.toString(STATE_TTL.toSeconds()),
        driverId.toString(),
        vehicleType == null ? "" : LocationKeys.available(vehicleType));
  }

  /**
   * Forgets the driver's last sequence number: a new session numbers its positions from zero again
   * ({@link LocationUpdate#seq()}), and every one would otherwise be ignored as out of order.
   */
  void restartSequence(UUID driverId) {
    redis.opsForHash().delete(LocationKeys.state(driverId), "seq");
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

  /**
   * The last accepted position, unless it is as old as {@code stale-after-seconds}: the same
   * cut-off at which the stale sweep takes a driver off the map.
   */
  @Override
  public Optional<GeoPoint> currentPosition(UUID driverId) {
    List<Object> fields =
        redis
            .opsForHash()
            .multiGet(LocationKeys.state(driverId), List.<Object>of("lat", "lng", "ts"));
    if (fields.contains(null)) {
      return Optional.empty();
    }
    long ageMs = clock.millis() - Long.parseLong(String.valueOf(fields.get(2)));
    if (ageMs >= Duration.ofSeconds(properties.location().staleAfterSeconds()).toMillis()) {
      return Optional.empty();
    }
    return Optional.of(
        new GeoPoint(
            Double.parseDouble(String.valueOf(fields.get(0))),
            Double.parseDouble(String.valueOf(fields.get(1)))));
  }

  @Override
  public Optional<Instant> lastReportedAt(UUID driverId) {
    Object ts = redis.opsForHash().get(LocationKeys.state(driverId), "ts");
    return Optional.ofNullable(ts)
        .map(t -> Instant.ofEpochMilli(Long.parseLong(String.valueOf(t))));
  }

  /**
   * Records which trip the driver is on, for the update script to hand back with each position - or
   * clears it. Written from the trip module's answer, not from whichever event arrived, so a late
   * or repeated event cannot attach a driver to a trip that is over.
   */
  void applyTripContext(UUID driverId, Optional<ActiveTrip> trip) {
    String key = LocationKeys.state(driverId);
    if (trip.isEmpty()) {
      redis.opsForHash().delete(key, TRIP_FIELDS.toArray());
      return;
    }
    ActiveTrip active = trip.get();
    boolean underWay = active.status() == TripStatus.IN_PROGRESS;
    GeoPoint target = underWay ? active.drop() : active.pickup();
    redis.execute(
        tripContextScript,
        List.of(key),
        Long.toString(STATE_TTL.toSeconds()),
        "tripId",
        active.tripId().toString(),
        "riderId",
        active.riderId().toString(),
        "tripPhase",
        (underWay ? TripPhase.TO_DROP : TripPhase.TO_PICKUP).name(),
        "targetLat",
        Double.toString(target.latitude()),
        "targetLng",
        Double.toString(target.longitude()));
  }

  /** The trip a driver was on, as last recorded here: who to tell when they go quiet. */
  Optional<TripContext> tripContext(UUID driverId) {
    List<Object> fields =
        redis
            .opsForHash()
            .multiGet(LocationKeys.state(driverId), List.<Object>of("tripId", "riderId"));
    if (fields.contains(null)) {
      return Optional.empty();
    }
    return Optional.of(
        new TripContext(
            UUID.fromString(String.valueOf(fields.get(0))),
            UUID.fromString(String.valueOf(fields.get(1)))));
  }

  /**
   * Everyone pinged within the stale window: one range read, then every state hash in one pipelined
   * round trip. A driver whose hash lapsed between the two is left out.
   */
  @Override
  public List<LiveDriver> liveDrivers() {
    long since =
        clock.millis() - Duration.ofSeconds(properties.location().staleAfterSeconds()).toMillis();
    List<String> ids =
        List.copyOf(
            redis
                .opsForZSet()
                .rangeByScore(LocationKeys.LAST_SEEN, since, Double.POSITIVE_INFINITY));
    List<Object> states =
        redis.executePipelined(
            (RedisCallback<Object>)
                connection -> {
                  for (String id : ids) {
                    connection
                        .hashCommands()
                        .hMGet(
                            LocationKeys.state(UUID.fromString(id))
                                .getBytes(StandardCharsets.UTF_8),
                            LIVE_FIELDS);
                  }
                  return null;
                });

    List<LiveDriver> live = new ArrayList<>(ids.size());
    for (int i = 0; i < ids.size(); i++) {
      @SuppressWarnings("unchecked")
      List<String> state = (List<String>) states.get(i);
      if (state.get(2) == null || state.get(3) == null) {
        continue;
      }
      live.add(
          new LiveDriver(
              UUID.fromString(ids.get(i)),
              state.get(0),
              state.get(1) == null ? null : VehicleType.valueOf(state.get(1)),
              toMetre(state.get(2)),
              toMetre(state.get(3)),
              state.get(4),
              state.get(5) == null ? null : toMetre(state.get(5)),
              state.get(6) == null ? null : toMetre(state.get(6))));
    }
    return live;
  }

  /**
   * Five decimal places, about a metre: plenty for a map, and a quarter off a snapshot that carries
   * every driver's position every two seconds.
   */
  private static double toMetre(String degrees) {
    return Math.round(Double.parseDouble(degrees) * 1e5) / 1e5;
  }

  /** Number of drivers currently listed as available for a type, for the gauge. */
  long availableCount(VehicleType type) {
    Long size = redis.opsForZSet().size(LocationKeys.available(type));
    return size == null ? 0 : size;
  }

  record StaleDriver(UUID driverId, long lastSeenMs) {}

  /** Before pickup the rider waits for the driver; after it they ride together. */
  enum TripPhase {
    TO_PICKUP,
    TO_DROP
  }

  record TripContext(UUID tripId, UUID riderId) {}

  /**
   * A position accepted for a driver on a trip: what to record, and whether to show the rider.
   *
   * @param vehicleType null if the driver's state never recorded one
   */
  record OnTrip(
      UUID tripId,
      UUID riderId,
      TripPhase phase,
      GeoPoint target,
      boolean forward,
      VehicleType vehicleType) {}

  /** What the update script decided, and what it did. */
  /**
   * @param onTrip set when an accepted position belongs to a driver on a trip
   */
  record UpdateOutcome(String result, int spoofStrikes, boolean geoWritten, OnTrip onTrip) {

    static UpdateOutcome from(List<Object> raw) {
      if (raw == null || raw.size() < 3) {
        throw new IllegalStateException("location_update.lua returned an unexpected shape: " + raw);
      }
      OnTrip onTrip = null;
      if (raw.size() >= 10 && !String.valueOf(raw.get(5)).isEmpty()) {
        String vehicleType = String.valueOf(raw.get(9));
        onTrip =
            new OnTrip(
                UUID.fromString(String.valueOf(raw.get(3))),
                UUID.fromString(String.valueOf(raw.get(4))),
                TripPhase.valueOf(String.valueOf(raw.get(5))),
                new GeoPoint(
                    Double.parseDouble(String.valueOf(raw.get(6))),
                    Double.parseDouble(String.valueOf(raw.get(7)))),
                "1".equals(String.valueOf(raw.get(8))),
                vehicleType.isEmpty() ? null : VehicleType.valueOf(vehicleType));
      }
      return new UpdateOutcome(
          String.valueOf(raw.get(0)),
          Integer.parseInt(String.valueOf(raw.get(1))),
          "1".equals(String.valueOf(raw.get(2))),
          onTrip);
    }

    boolean accepted() {
      return "accepted".equals(result);
    }
  }
}
