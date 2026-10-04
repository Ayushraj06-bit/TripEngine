package com.rideflow.location.internal;

import com.rideflow.location.internal.DriverStateStore.OnTrip;
import com.rideflow.location.internal.DriverStateStore.TripPhase;
import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What happens to an accepted position of a driver on a trip: recorded on the route once the rider
 * is aboard, and shown to the rider - at most every {@code driver-location-forward-ms} - with an
 * ETA to the pickup or the drop.
 *
 * <p>A second round trip, but only for drivers on a trip. Available drivers, the bulk of the
 * traffic, keep section 8's single round trip per update.
 */
@Component
class LiveTripRelay {

  static final String DESTINATION = "/queue/driver-location";

  private static final Logger log = LoggerFactory.getLogger(LiveTripRelay.class);

  /** What the rider sees. {@code etaSeconds} is null when the vehicle type is unknown. */
  record DriverLocation(double lat, double lng, TripPhase phase, Long etaSeconds) {}

  private final TripRouteStore routes;
  private final RealtimePublisher realtime;
  private final RideFlowProperties properties;

  LiveTripRelay(TripRouteStore routes, RealtimePublisher realtime, RideFlowProperties properties) {
    this.routes = routes;
    this.realtime = realtime;
    this.properties = properties;
  }

  void relay(UUID driverId, GeoPoint position, Instant at, OnTrip trip) {
    if (trip.phase() == TripPhase.TO_DROP) {
      routes.append(trip.tripId(), position, at);
    }
    if (!trip.forward()) {
      return;
    }
    try {
      realtime.toUser(
          trip.riderId(),
          DESTINATION,
          RealtimeMessage.forTrip(
              "DRIVER_LOCATION",
              trip.tripId(),
              new DriverLocation(
                  position.latitude(), position.longitude(), trip.phase(), eta(position, trip))));
    } catch (RuntimeException e) {
      // Best effort: the next position, two seconds on, tries again.
      log.warn("Could not show driver {}'s position to their rider", driverId, e);
    }
  }

  /**
   * Straight-line distance stretched by the road factor, at the vehicle's average city speed: the
   * same estimate a quote uses, so the ETA and the fare agree about the city.
   */
  private Long eta(GeoPoint position, OnTrip trip) {
    if (trip.vehicleType() == null) {
      return null;
    }
    var pricing = properties.pricing();
    double meters = position.distanceMetersTo(trip.target()) * pricing.roadFactor();
    // Multiplied before dividing, as in pricing: a pre-computed metres-per-second is
    // rounded, and the ceiling turns that into a stray extra second.
    return (long)
        Math.ceil(meters * 3600.0 / (pricing.averageSpeedKmph().of(trip.vehicleType()) * 1000.0));
  }
}
