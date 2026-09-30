package com.rideflow.pricing;

import com.rideflow.shared.Money;
import java.util.Objects;

/**
 * How a fare was built, line by line, so the app can show the rider where the price came from.
 *
 * <p>The lines always sum exactly to the total - enforced here, so a breakdown that does not add up
 * cannot be constructed. Rounding the total to a whole rupee (PROJECT_SPEC section 7) would
 * otherwise leave up to fifty paise unaccounted for, and a rider adding the lines up would find the
 * total wrong.
 *
 * @param minimumFareTopUp what was added to reach the vehicle's minimum fare; zero on a long trip
 * @param surgeCharge what the surge multiplier added; zero at 1.0
 * @param roundingPaise the adjustment that rounds the total to a whole rupee: between -49 and +50
 *     paise. Signed, which is why it is paise rather than {@link Money}, which cannot be negative.
 */
public record FareBreakdown(
    Money baseFare,
    Money distanceFare,
    Money timeFare,
    Money minimumFareTopUp,
    Money surgeCharge,
    long roundingPaise,
    Money total) {

  public FareBreakdown {
    Objects.requireNonNull(baseFare, "baseFare");
    Objects.requireNonNull(distanceFare, "distanceFare");
    Objects.requireNonNull(timeFare, "timeFare");
    Objects.requireNonNull(minimumFareTopUp, "minimumFareTopUp");
    Objects.requireNonNull(surgeCharge, "surgeCharge");
    Objects.requireNonNull(total, "total");
    long lines =
        baseFare.paise()
            + distanceFare.paise()
            + timeFare.paise()
            + minimumFareTopUp.paise()
            + surgeCharge.paise()
            + roundingPaise;
    if (lines != total.paise()) {
      throw new IllegalArgumentException(
          "fare lines sum to %d paise but the total is %d".formatted(lines, total.paise()));
    }
  }
}
