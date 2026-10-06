package com.rideflow.trip.internal;

import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripEvent;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Pushes every change to a trip to its rider and driver, on {@code /user/queue/trip-updates}.
 *
 * <p>The payload is the recipient's own view of the trip as it is now - the same as {@code GET
 * /trips/{id}} would answer them, OTP only in the rider's copy - rather than a description of the
 * change. A state can be applied twice harmlessly; a change cannot. The envelope's {@code seq} lets
 * the client drop anything older than what it already shows - which holds only because the number
 * is reserved before the state is read.
 *
 * <p>No surrounding transaction: one read, then Redis, and section 11 keeps Redis out of
 * transactions.
 */
@Component
class TripUpdatePusher {

  static final String DESTINATION = "/queue/trip-updates";

  private final TripRepository trips;
  private final RealtimePublisher realtime;

  TripUpdatePusher(TripRepository trips, RealtimePublisher realtime) {
    this.trips = trips;
    this.realtime = realtime;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripEvent event) {
    // The number first, then the state. Two events of one trip are handled at once: numbered
    // after reading, the slower could send an older state under a newer number, and the client
    // would show it and drop the real one. One number for both copies: each client still sees
    // its numbers rise.
    long seq = realtime.nextSeq(event.tripId());
    trips
        .findById(event.tripId())
        .ifPresent(
            trip -> {
              String type = event.getClass().getSimpleName();
              push(trip.riderId(), type, seq, trip, TripActor.RIDER);
              // A driver who backed out is no longer on the trip, and gets nothing.
              if (trip.driverId() != null) {
                push(trip.driverId(), type, seq, trip, TripActor.DRIVER);
              }
            });
  }

  private void push(UUID userId, String type, long seq, Trip trip, TripActor viewer) {
    realtime.toUser(
        userId, DESTINATION, RealtimeMessage.forTrip(type, trip.id(), seq, trip.view(viewer)));
  }
}
