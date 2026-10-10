package com.rideflow.payment.internal;

import com.rideflow.shared.Money;

/**
 * A charge divided between the driver and the platform (PROJECT_SPEC section 7): the commission
 * rounded HALF_EVEN, and the rest - remainder included - to the driver. The two always add up to
 * the charge exactly.
 */
record CommissionSplit(Money driverShare, Money commission) {

  static CommissionSplit of(Money charge, int commissionPercent) {
    Money commission = charge.percentage(commissionPercent);
    return new CommissionSplit(charge.minus(commission), commission);
  }
}
