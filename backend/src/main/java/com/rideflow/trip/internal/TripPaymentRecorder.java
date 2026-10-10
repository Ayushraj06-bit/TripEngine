package com.rideflow.trip.internal;

import com.rideflow.shared.IdGenerator;
import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.RatableTrips;
import com.rideflow.trip.TripPaymentStatusChanged;
import com.rideflow.trip.TripPayments;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trip's side of payment: its {@code payment_status}, and the facts ratings need.
 *
 * <p>Payment status is written by one conditional UPDATE that leaves the version alone, as flags
 * are. A completed trip is otherwise finished with, and a payment arriving must never turn into a
 * 409 for whatever else touches the trip at that moment.
 *
 * <p>No {@code trip_events} row: that log is of the state machine, and payment status is not a
 * state. The ledger is the record of the money.
 */
@Component
class TripPaymentRecorder implements TripPayments, RatableTrips {

  private final TripRepository trips;
  private final ApplicationEventPublisher events;
  private final IdGenerator ids;
  private final Clock clock;

  TripPaymentRecorder(
      TripRepository trips, ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
    this.trips = trips;
    this.events = events;
    this.ids = ids;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordPaymentStatus(UUID tripId, PaymentStatus status) {
    trips.findById(tripId).ifPresent(trip -> change(trip, status));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void settleOutstanding(UUID riderId) {
    for (Trip trip :
        trips.findByRiderIdAndPaymentStatus(riderId, PaymentStatus.PENDING_SETTLEMENT)) {
      change(trip, PaymentStatus.PAID);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RatableTrip> find(UUID tripId) {
    return trips
        .findById(tripId)
        .map(
            trip ->
                new RatableTrip(
                    trip.id(), trip.riderId(), trip.driverId(), trip.status(), trip.completedAt()));
  }

  private void change(Trip trip, PaymentStatus status) {
    if (trips.setPaymentStatus(trip.id(), status) == 1) {
      events.publishEvent(
          new TripPaymentStatusChanged(
              ids.newId(), clock.instant(), trip.id(), trip.riderId(), trip.driverId(), status));
    }
  }
}
