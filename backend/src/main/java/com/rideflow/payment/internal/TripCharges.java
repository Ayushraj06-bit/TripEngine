package com.rideflow.payment.internal;

import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.TripPayments;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a trip costs the rider, and who gets it (ADR 0012).
 *
 * <ul>
 *   <li><strong>Wallet trip</strong>: the fare from the rider's wallet, split between the driver's
 *       earnings and the platform's commission.
 *   <li><strong>Cash trip</strong>: the rider paid the driver in cash, so the driver owes the
 *       platform its commission: from their earnings to platform revenue.
 *   <li><strong>Cancellation fee</strong>: as a wallet trip, whatever the payment method - no cash
 *       changes hands for a ride that never happened.
 * </ul>
 *
 * <p>A wallet charge is taken even when the wallet cannot cover it. The wallet goes below zero, the
 * driver is paid regardless, and the trip waits as {@code PENDING_SETTLEMENT}, which blocks the
 * rider from booking until a top-up brings the wallet back to zero or above.
 */
@Component
class TripCharges {

  private final Ledger ledger;
  private final Postings postings;
  private final TripPayments tripPayments;
  private final int commissionPercent;

  TripCharges(
      Ledger ledger, Postings postings, TripPayments tripPayments, RideFlowProperties properties) {
    this.ledger = ledger;
    this.postings = postings;
    this.tripPayments = tripPayments;
    this.commissionPercent = properties.payment().commissionPercent();
  }

  /** Charges a completed trip's fare, once however often it is asked. */
  void chargeFare(UUID tripId, UUID riderId, UUID driverId, Money fare, PaymentMethod method) {
    postings.run(
        () -> {
          if (method == PaymentMethod.WALLET) {
            fromWallet(TransactionType.TRIP_CHARGE, tripId, riderId, driverId, fare);
            return;
          }
          Money commission = CommissionSplit.of(fare, commissionPercent).commission();
          if (commission.isPositive()) {
            ledger.post(
                TransactionType.CASH_COMMISSION,
                tripId,
                List.of(
                    Leg.debit(AccountKey.earnings(driverId), commission),
                    Leg.credit(AccountKey.REVENUE, commission)));
          }
          // The rider paid the driver at the kerb: settled whatever the ledger says.
          tripPayments.recordPaymentStatus(tripId, PaymentStatus.PAID);
        });
  }

  /** Charges a cancellation or no-show fee to the rider's wallet, once. */
  void chargeCancellationFee(UUID tripId, UUID riderId, UUID driverId, Money fee) {
    postings.run(
        () -> fromWallet(TransactionType.CANCELLATION_FEE, tripId, riderId, driverId, fee));
  }

  private void fromWallet(
      TransactionType type, UUID tripId, UUID riderId, UUID driverId, Money amount) {
    CommissionSplit split = CommissionSplit.of(amount, commissionPercent);
    List<Leg> legs = new ArrayList<>();
    legs.add(Leg.debit(AccountKey.wallet(riderId), amount));
    if (split.driverShare().isPositive()) {
      legs.add(Leg.credit(AccountKey.earnings(driverId), split.driverShare()));
    }
    if (split.commission().isPositive()) {
      legs.add(Leg.credit(AccountKey.REVENUE, split.commission()));
    }
    ledger
        .post(type, tripId, legs)
        .ifPresent(
            posted ->
                tripPayments.recordPaymentStatus(
                    tripId,
                    posted.balanceOf(AccountKey.wallet(riderId)) < 0
                        ? PaymentStatus.PENDING_SETTLEMENT
                        : PaymentStatus.PAID));
  }
}
