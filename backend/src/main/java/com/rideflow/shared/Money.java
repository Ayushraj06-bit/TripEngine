package com.rideflow.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A non-negative amount of money, held as whole paise.
 *
 * <p>PROJECT_SPEC section 7 forbids {@code double} and {@code float} for money: binary floating
 * point cannot represent 0.10 exactly, so repeated fare arithmetic drifts. Every amount is a {@code
 * long} count of paise.
 *
 * <p>Amounts cannot be negative. Direction is expressed by the ledger, not by the sign: section 7
 * gives {@code ledger_entries} a {@code direction} of DEBIT or CREDIT with {@code amount_paise >
 * 0}, so a signed amount is never needed and a negative one is always a bug.
 *
 * <p>The rounding mode is fixed per operation rather than passed in, because section 7 fixes it per
 * use: HALF_UP for fares, HALF_EVEN for commission splits. A caller cannot pick the wrong one.
 *
 * <p>Arithmetic overflows throw {@link ArithmeticException} rather than wrapping around silently.
 */
public record Money(long paise) implements Comparable<Money> {

  /** Zero rupees. */
  public static final Money ZERO = new Money(0);

  private static final long PAISE_PER_RUPEE = 100L;
  private static final BigDecimal PAISE_PER_RUPEE_DECIMAL = BigDecimal.valueOf(PAISE_PER_RUPEE);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public Money {
    if (paise < 0) {
      throw new IllegalArgumentException("money must not be negative, was " + paise + " paise");
    }
  }

  /** An amount of {@code paise}. */
  public static Money ofPaise(long paise) {
    return new Money(paise);
  }

  /**
   * An amount of whole rupees.
   *
   * @throws ArithmeticException if the amount overflows a {@code long} of paise
   */
  public static Money ofRupees(long rupees) {
    return new Money(Math.multiplyExact(rupees, PAISE_PER_RUPEE));
  }

  /**
   * This amount plus {@code other}.
   *
   * @throws ArithmeticException on overflow
   */
  public Money plus(Money other) {
    Objects.requireNonNull(other, "other");
    return new Money(Math.addExact(paise, other.paise));
  }

  /**
   * This amount minus {@code other}.
   *
   * @throws IllegalArgumentException if the result would be negative
   */
  public Money minus(Money other) {
    Objects.requireNonNull(other, "other");
    long result = Math.subtractExact(paise, other.paise);
    if (result < 0) {
      throw new IllegalArgumentException(
          "subtracting %d paise from %d paise would be negative".formatted(other.paise, paise));
    }
    return new Money(result);
  }

  /**
   * This amount scaled by {@code multiplier}, rounded HALF_UP to whole paise.
   *
   * <p>Used for the surge multiplier.
   *
   * @throws IllegalArgumentException if {@code multiplier} is negative
   */
  public Money times(BigDecimal multiplier) {
    Objects.requireNonNull(multiplier, "multiplier");
    if (multiplier.signum() < 0) {
      throw new IllegalArgumentException("multiplier must not be negative, was " + multiplier);
    }
    return new Money(
        BigDecimal.valueOf(paise)
            .multiply(multiplier)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact());
  }

  /**
   * The given percentage of this amount, rounded HALF_EVEN.
   *
   * <p>PROJECT_SPEC section 7 requires HALF_EVEN for commission splits so that a long run of splits
   * does not drift upwards the way HALF_UP would. The other side of the split is derived with
   * {@link #minus}, which is what gives the remainder to the driver and keeps the two parts summing
   * exactly to the whole.
   *
   * @throws IllegalArgumentException if {@code percent} is outside 0 to 100
   */
  public Money percentage(int percent) {
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be in [0, 100], was " + percent);
    }
    return new Money(
        BigDecimal.valueOf(paise)
            .multiply(BigDecimal.valueOf(percent))
            .divide(HUNDRED, 0, RoundingMode.HALF_EVEN)
            .longValueExact());
  }

  /**
   * This amount rounded HALF_UP to a whole number of rupees.
   *
   * <p>PROJECT_SPEC section 7 requires HALF_UP for fares, so riders never see a fare in paise.
   */
  public Money roundedToRupee() {
    return new Money(
        BigDecimal.valueOf(paise)
            .divide(PAISE_PER_RUPEE_DECIMAL, 0, RoundingMode.HALF_UP)
            .multiply(PAISE_PER_RUPEE_DECIMAL)
            .longValueExact());
  }

  /** The larger of this amount and {@code other}, used to apply a minimum fare. */
  public Money max(Money other) {
    Objects.requireNonNull(other, "other");
    return paise >= other.paise ? this : other;
  }

  /** The smaller of this amount and {@code other}, used to cap a charge at a balance. */
  public Money min(Money other) {
    Objects.requireNonNull(other, "other");
    return paise <= other.paise ? this : other;
  }

  public boolean isZero() {
    return paise == 0;
  }

  public boolean isPositive() {
    return paise > 0;
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public boolean isLessThan(Money other) {
    return compareTo(other) < 0;
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(paise, other.paise);
  }

  /**
   * This amount formatted as rupees and paise, for example {@code 1234} becomes {@code 12.34}.
   *
   * <p>For display and logs only. Never parse this back into a {@code Money}.
   */
  public String toRupeeString() {
    return "%d.%02d".formatted(paise / PAISE_PER_RUPEE, paise % PAISE_PER_RUPEE);
  }
}
