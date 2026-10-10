package com.rideflow.pricing.internal;

import com.rideflow.shared.RideFlowProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The surge multiplier for a cell, from its demand and supply (PROJECT_SPEC section 8). Pure.
 *
 * <ul>
 *   <li><strong>Target</strong>: demand over supply - supply at least one, so no driver at all is a
 *       ratio, not a division by zero - looked up in the step table. Above the last step, the cap.
 *   <li><strong>Smoothing</strong>: from the current value towards the target by at most {@code
 *       max-step-per-tick}, so a price moves in steps a rider can see coming, up and down.
 *   <li><strong>Pricing</strong>: the smoothed value rounded to 0.1 for the fare.
 * </ul>
 *
 * <p>The smoothed value is kept exact between ticks and rounded only for pricing. Rounding it every
 * tick would turn steps of 0.25 into 0.3, 0.3, 0.2 ... and never quite reach the target.
 *
 * <p>Always within 1.0 and the cap.
 */
final class SurgeCalculator {

  static final BigDecimal NONE = BigDecimal.ONE;

  private final List<RideFlowProperties.Surge.Step> steps;
  private final BigDecimal maxStep;
  private final BigDecimal cap;

  SurgeCalculator(RideFlowProperties.Surge rules) {
    this.steps = List.copyOf(rules.steps());
    this.maxStep = BigDecimal.valueOf(rules.maxStepPerTick());
    this.cap = BigDecimal.valueOf(rules.cap());
  }

  /** Where the multiplier should head for this much demand and supply. */
  BigDecimal target(long demand, long supply) {
    if (demand <= 0) {
      return NONE;
    }
    BigDecimal ratio =
        BigDecimal.valueOf(demand)
            .divide(BigDecimal.valueOf(Math.max(supply, 1)), 6, RoundingMode.HALF_UP);
    for (RideFlowProperties.Surge.Step step : steps) {
      if (ratio.compareTo(BigDecimal.valueOf(step.maxRatio())) <= 0) {
        return clamp(BigDecimal.valueOf(step.multiplier()));
      }
    }
    return cap;
  }

  /** One tick's move from {@code current} towards {@code target}. */
  BigDecimal smooth(BigDecimal current, BigDecimal target) {
    BigDecimal from = clamp(current);
    BigDecimal to = clamp(target);
    BigDecimal gap = to.subtract(from);
    if (gap.abs().compareTo(maxStep) <= 0) {
      return to;
    }
    return clamp(gap.signum() > 0 ? from.add(maxStep) : from.subtract(maxStep));
  }

  /** The multiplier a fare is priced at: rounded to 0.1, within 1.0 and the cap. */
  BigDecimal forPricing(BigDecimal value) {
    return clamp(value.setScale(1, RoundingMode.HALF_UP));
  }

  BigDecimal cap() {
    return cap;
  }

  private BigDecimal clamp(BigDecimal value) {
    if (value.compareTo(NONE) < 0) {
      return NONE;
    }
    return value.compareTo(cap) > 0 ? cap : value.stripTrailingZeros();
  }
}
