package com.rideflow.location.internal;

import com.rideflow.location.AvailableDriver;
import com.rideflow.location.AvailableDriverFinder;
import com.rideflow.location.NearbyDriver;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.ServiceArea;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reads of the live map: the rider's "cars around you" view, and the search matching uses.
 *
 * <p>The two are deliberately different. Matching gets exact positions and driver ids because it
 * has to offer a specific driver a specific trip. Riders get neither: an exact position plus a
 * stable id would let anyone follow a named driver around the city from a phone, and the view only
 * exists to show that the service is alive nearby.
 */
@Service
public class NearbyDriverService implements AvailableDriverFinder {

  /**
   * How far to look for the rider's view. Wide enough to look populated, and unrelated to
   * matching's widening rings, which are about who can actually be dispatched.
   */
  private static final double NEARBY_RADIUS_KM = 5.0;

  /**
   * A bound on distinct cache cells held at once. The cache is keyed by area, so an unbounded map
   * would grow with every place anyone ever opened the app.
   */
  private static final int MAX_CACHE_CELLS = 10_000;

  private final DriverStateStore store;
  private final ServiceArea serviceArea;
  private final RideFlowProperties properties;
  private final Clock clock;

  private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

  NearbyDriverService(
      DriverStateStore store, ServiceArea serviceArea, RideFlowProperties properties, Clock clock) {
    this.store = store;
    this.serviceArea = serviceArea;
    this.properties = properties;
    this.clock = clock;
  }

  /** The exact search matching needs. Radius inclusive, nearest first. */
  @Override
  public List<AvailableDriver> findAvailableDrivers(
      GeoPoint centre, VehicleType type, double radiusKm, int limit) {
    return store.search(centre, type, radiusKm, limit);
  }

  /**
   * Approximate positions for a rider.
   *
   * <p>Cached for a couple of seconds per area, because in a busy district hundreds of riders open
   * the app at once and ask an identical question; without it each one is a separate GEOSEARCH for
   * an answer that has not meaningfully changed.
   */
  public List<NearbyDriver> nearby(GeoPoint centre, VehicleType type) {
    if (!serviceArea.contains(centre)) {
      throw new BusinessException(
          "OUTSIDE_SERVICE_AREA",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "RideFlow does not operate at that location yet");
    }

    String key = cellKey(centre, type);
    long nowMs = clock.millis();

    CachedAnswer cached = cache.get(key);
    if (cached != null && cached.expiresAtMs() > nowMs) {
      return cached.drivers();
    }

    List<NearbyDriver> answer =
        store
            .nearbyPositions(
                centre, type, NEARBY_RADIUS_KM, properties.location().nearbyResultLimit())
            .stream()
            .map(this::blur)
            .toList();

    if (cache.size() >= MAX_CACHE_CELLS) {
      // Crude, and fine: entries live two seconds, so the whole map is stale almost
      // immediately anyway. An eviction policy would be machinery for nothing.
      cache.clear();
    }
    cache.put(
        key, new CachedAnswer(answer, nowMs + properties.location().nearbyCacheSeconds() * 1000L));
    return answer;
  }

  /**
   * Discards every cached answer.
   *
   * <p>For tests, which share one context and move the clock backwards between cases; a cached
   * answer from an earlier test would otherwise still look fresh.
   */
  void clearCache() {
    cache.clear();
  }

  /**
   * Rounds a position so it identifies an area rather than a car.
   *
   * <p>Three decimal places is roughly 110 metres, which is enough to draw a plausible map and not
   * enough to walk up to a specific vehicle.
   */
  private NearbyDriver blur(GeoPoint exact) {
    int decimals = properties.location().nearbyRoundingDecimals();
    return new NearbyDriver(round(exact.latitude(), decimals), round(exact.longitude(), decimals));
  }

  private static double round(double value, int decimals) {
    return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
  }

  /**
   * The cache cell for a request.
   *
   * <p>Derived from the same rounding as the response, so two riders who would be told the same
   * thing share an entry, and the cell can never be finer or coarser than the answer it holds.
   */
  private String cellKey(GeoPoint centre, VehicleType type) {
    int decimals = properties.location().nearbyRoundingDecimals();
    return "%s:%s:%s"
        .formatted(
            type.name(), round(centre.latitude(), decimals), round(centre.longitude(), decimals));
  }

  private record CachedAnswer(List<NearbyDriver> drivers, long expiresAtMs) {}
}
