package com.rideflow.payment.internal;

import java.util.UUID;

/** Which account: its owner and its type. The platform's accounts are owned by the nil id. */
record AccountKey(UUID ownerId, AccountType type) {

  static final UUID PLATFORM = new UUID(0, 0);

  static final AccountKey REVENUE = new AccountKey(PLATFORM, AccountType.PLATFORM_REVENUE);

  static final AccountKey CLEARING = new AccountKey(PLATFORM, AccountType.PLATFORM_CLEARING);

  static AccountKey wallet(UUID riderId) {
    return new AccountKey(riderId, AccountType.RIDER_WALLET);
  }

  static AccountKey earnings(UUID driverId) {
    return new AccountKey(driverId, AccountType.DRIVER_EARNINGS);
  }
}
