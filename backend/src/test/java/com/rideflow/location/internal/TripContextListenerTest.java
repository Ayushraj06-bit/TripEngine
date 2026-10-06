package com.rideflow.location.internal;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rideflow.shared.GeoPoint;
import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.DriverTrips;
import com.rideflow.trip.TripStarted;
import com.rideflow.trip.TripStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TripContextListenerTest {

  private final DriverTrips driverTrips = mock(DriverTrips.class);
  private final DriverStateStore store = mock(DriverStateStore.class);
  private final TripContextListener listener = new TripContextListener(driverTrips, store);

  private final UUID driverId = UUID.randomUUID();
  private final ActiveTrip underWay =
      new ActiveTrip(
          UUID.randomUUID(),
          UUID.randomUUID(),
          TripStatus.IN_PROGRESS,
          new GeoPoint(12.9752, 77.6064),
          new GeoPoint(13.0352, 77.6064));

  private TripStarted started() {
    return new TripStarted(
        UUID.randomUUID(),
        Instant.parse("2026-10-01T10:00:00Z"),
        underWay.tripId(),
        underWay.riderId(),
        driverId);
  }

  /**
   * The trip was under way when read, and over by the time that answer was written - the complete
   * event's own listener having written first. The check after writing catches it.
   */
  @Test
  void rewritesWhenTheTripMovedOnWhileItWasBeingWritten() {
    when(driverTrips.activeTrip(driverId))
        .thenReturn(Optional.of(underWay), Optional.empty(), Optional.empty());

    listener.on(started());

    InOrder order = inOrder(store);
    order.verify(store).applyTripContext(driverId, Optional.of(underWay));
    order.verify(store).applyTripContext(driverId, Optional.empty());
    order.verifyNoMoreInteractions();
  }

  @Test
  void writesOnceWhenNothingMoved() {
    when(driverTrips.activeTrip(driverId)).thenReturn(Optional.of(underWay));

    listener.on(started());

    verify(store, times(1)).applyTripContext(driverId, Optional.of(underWay));
  }
}
