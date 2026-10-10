package com.rideflow.payment.internal;

import com.rideflow.shared.Money;

/** One entry of a transaction yet to be posted. */
record Leg(AccountKey account, Direction direction, Money amount) {

  static Leg debit(AccountKey account, Money amount) {
    return new Leg(account, Direction.DEBIT, amount);
  }

  static Leg credit(AccountKey account, Money amount) {
    return new Leg(account, Direction.CREDIT, amount);
  }
}
