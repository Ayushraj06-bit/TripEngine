package com.rideflow.payment.internal;

import com.rideflow.shared.Money;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Charges trips as they end (PROJECT_SPEC section 8.6). Events arrive at least once; the ledger's
 * unique type and reference makes a second delivery charge nothing.
 *
 * <p>No surrounding transaction: {@link Postings} opens its own, so that one lost to a concurrent
 * top-up can be run again.
 */
@Component
class TripPaymentListener {

  private final TripCharges charges;

  TripPaymentListener(TripCharges charges) {
    this.charges = charges;
  }

  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripCompleted completed) {
    charges.chargeFare(
        completed.tripId(),
        completed.riderId(),
        completed.driverId(),
        Money.ofPaise(completed.finalFarePaise()),
        completed.paymentMethod());
  }

  /** A fee only once a driver was on the way; a free cancellation moves no money. */
  @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
  void on(TripCancelled cancelled) {
    if (cancelled.cancellationFeePaise() > 0 && cancelled.driverId() != null) {
      charges.chargeCancellationFee(
          cancelled.tripId(),
          cancelled.riderId(),
          cancelled.driverId(),
          Money.ofPaise(cancelled.cancellationFeePaise()));
    }
  }
}
