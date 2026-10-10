package com.rideflow.payment.internal;

/** Where a top-up stands: PENDING until the gateway's webhook says how it ended. */
public enum TopupStatus {
  PENDING,
  SUCCEEDED,
  FAILED
}
