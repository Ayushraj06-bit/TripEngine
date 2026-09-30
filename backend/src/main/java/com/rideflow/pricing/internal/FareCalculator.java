package com.rideflow.pricing.internal;

import com.rideflow.pricing.FareBreakdown;
import com.rideflow.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The fare formula, as a pure function.
 *
 * <p>No Spring, no clock, no database: given rates, a route and a surge multiplier, the fare is
 * fully determined, which is what makes it table-testable and safe to reuse when Phase 6 reprices a
 * finished trip.
 *
 * <p>The formula is the one agreed for Phase 3:
 *
 * <pre>
 *   fare = max(minimumFare, base + perKm x km + perMinute x minutes) x surge
 * </pre>
 *
 * rounded HALF_UP to a whole rupee (PROJECT_SPEC section 7). The minimum fare applies
 * <em>before</em> surge, so a short trip at 2.5x costs 2.5 minimum fares.
 *
 * <p>Intermediate amounts - the distance part, the time part, the surged amount - are rounded
 * HALF_UP to whole paise, because {@link Money} holds whole paise. The effect on the total is at
 * most a paisa before the final rounding to a rupee absorbs it.
 */
final class FareCalculator {

  private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);
  private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

  /** Enough to make the minutes conversion exact to far below a paisa. */
  private static final int MINUTES_SCALE = 12;

  private FareCalculator() {}

  /**
   * @param surge at least 1.0: surge never makes a trip cheaper
   * @throws IllegalArgumentException for a negative distance or duration, or a surge below 1.0
   */
  static FareBreakdown calculate(
      RateCard card, long distanceMeters, long durationSeconds, BigDecimal surge) {
    Objects.requireNonNull(card, "card");
    Objects.requireNonNull(surge, "surge");
    if (distanceMeters < 0 || durationSeconds < 0) {
      throw new IllegalArgumentException(
          "distance and duration must not be negative, were %d m and %d s"
              .formatted(distanceMeters, durationSeconds));
    }
    if (surge.compareTo(BigDecimal.ONE) < 0) {
      throw new IllegalArgumentException("surge must be at least 1.0, was " + surge);
    }

    Money base = card.base();
    Money distanceFare =
        card.perKm().times(BigDecimal.valueOf(distanceMeters).divide(METERS_PER_KM));
    Money timeFare =
        card.perMinute()
            .times(
                BigDecimal.valueOf(durationSeconds)
                    .divide(SECONDS_PER_MINUTE, MINUTES_SCALE, RoundingMode.HALF_UP));

    Money metered = base.plus(distanceFare).plus(timeFare);
    Money beforeSurge = metered.max(card.minimumFare());
    Money minimumFareTopUp = beforeSurge.minus(metered);

    Money surged = beforeSurge.times(surge);
    Money surgeCharge = surged.minus(beforeSurge);

    Money total = surged.roundedToRupee();
    long roundingPaise = total.paise() - surged.paise();

    return new FareBreakdown(
        base, distanceFare, timeFare, minimumFareTopUp, surgeCharge, roundingPaise, total);
  }
}
