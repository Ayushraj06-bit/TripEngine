package com.rideflow.trip.internal;

import com.rideflow.pricing.SurgeDemand;
import com.rideflow.shared.GeoPoint;
import com.rideflow.trip.TripRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Tells the surge engine about every ride request (PROJECT_SPEC section 8).
 *
 * <p>Here rather than in {@code pricing}: {@code trip} depends on {@code pricing}, so the event is
 * passed on from this side. The event id makes a redelivery count once. No transaction: it is one
 * Redis call.
 */
@Component
class SurgeDemandRecorder {

  private final SurgeDemand surgeDemand;

  SurgeDemandRecorder(SurgeDemand surgeDemand) {
    this.surgeDemand = surgeDemand;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripRequested requested) {
    surgeDemand.recordRequest(
        requested.eventId(),
        new GeoPoint(requested.pickupLat(), requested.pickupLng()),
        requested.occurredAt());
  }
}
