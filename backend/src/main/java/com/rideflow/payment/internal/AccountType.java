package com.rideflow.payment.internal;

/** The four kinds of ledger account (PROJECT_SPEC section 7, ADR 0012). */
public enum AccountType {
  /** What a rider has to spend. Negative while a trip waits on a top-up. */
  RIDER_WALLET(OwnerType.USER),
  /** What the platform owes a driver. Negative when they owe commission on cash trips. */
  DRIVER_EARNINGS(OwnerType.USER),
  /** Commission the platform has earned. */
  PLATFORM_REVENUE(OwnerType.PLATFORM),
  /** Money the gateway has taken in for the platform: the other side of every top-up. */
  PLATFORM_CLEARING(OwnerType.PLATFORM);

  private final OwnerType ownerType;

  AccountType(OwnerType ownerType) {
    this.ownerType = ownerType;
  }

  OwnerType ownerType() {
    return ownerType;
  }

  public enum OwnerType {
    USER,
    PLATFORM
  }
}
