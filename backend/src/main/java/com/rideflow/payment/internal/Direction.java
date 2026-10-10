package com.rideflow.payment.internal;

/** Which side of an account an entry is on. A balance is credits minus debits. */
public enum Direction {
  DEBIT,
  CREDIT;

  Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
