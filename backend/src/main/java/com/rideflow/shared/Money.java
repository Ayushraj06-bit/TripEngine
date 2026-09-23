package com.rideflow.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money, held as whole paise.
 *
 * <p>PROJECT_SPEC section 7 forbids {@code double} and {@code float} for money: binary floating
 * point cannot represent 0.10 exactly, so repeated fare arithmetic drifts. Every amount in RideFlow
 * is therefore a {@code long} count of paise, and every operation that cannot produce a whole
 * number takes an explicit {@link RoundingMode} so the caller has to decide how it rounds.
 *
 * <p>Negative amounts are allowed: the ledger expresses corrections and deltas with them. The
 * constraint that a ledger <em>entry</em> is strictly positive belongs to the payment module, not
 * to this type.
 *
 * <p>Arithmetic overflows throw {@link ArithmeticException} rather than wrapping around silently.
 */
public record Money(long paise) implements Comparable<Money> {

  /** Zero rupees. */
  public static final Money ZERO = new Money(0);

  private static final long PAISE_PER_RUPEE = 100L;
  private static final BigDecimal PAISE_PER_RUPEE_DECIMAL = BigDecimal.valueOf(PAISE_PER_RUPEE);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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
   * @throws ArithmeticException on overflow
   */
  public Money minus(Money other) {
    Objects.requireNonNull(other, "other");
    return new Money(Math.subtractExact(paise, other.paise));
  }

  /**
   * This amount with the opposite sign.
   *
   * @throws ArithmeticException on overflow
   */
  public Money negated() {
    return new Money(Math.negateExact(paise));
  }

  /** This amount with its sign removed. */
  public Money abs() {
    return paise < 0 ? negated() : this;
  }

  /**
   * This amount scaled by {@code factor}, rounded to whole paise.
   *
   * <p>Used for the surge multiplier, where the factor is not a whole number.
   *
   * @throws ArithmeticException if {@code mode} is {@link RoundingMode#UNNECESSARY} and rounding
   *     would be needed, or if the result overflows
   */
  public Money multipliedBy(BigDecimal factor, RoundingMode mode) {
    Objects.requireNonNull(factor, "factor");
    Objects.requireNonNull(mode, "mode");
    return new Money(BigDecimal.valueOf(paise).multiply(factor).setScale(0, mode).longValueExact());
  }

  /**
   * The given percentage of this amount, rounded to whole paise.
   *
   * <p>PROJECT_SPEC section 7 requires {@link RoundingMode#HALF_EVEN} for commission splits, and
   * that the remainder goes to the driver. This method only computes one side of such a split; the
   * policy of who gets the remainder belongs to the payment module, which derives the other side by
   * subtraction.
   *
   * @throws IllegalArgumentException if {@code percent} is negative
   */
  public Money percentage(int percent, RoundingMode mode) {
    if (percent < 0) {
      throw new IllegalArgumentException("percent must not be negative, was " + percent);
    }
    Objects.requireNonNull(mode, "mode");
    return new Money(
        BigDecimal.valueOf(paise)
            .multiply(BigDecimal.valueOf(percent))
            .divide(HUNDRED, 0, mode)
            .longValueExact());
  }

  /**
   * This amount rounded to a whole number of rupees.
   *
   * <p>PROJECT_SPEC section 7 requires {@link RoundingMode#HALF_UP} for fares, so riders never see
   * a fare in paise.
   */
  public Money roundedToRupee(RoundingMode mode) {
    Objects.requireNonNull(mode, "mode");
    return new Money(
        BigDecimal.valueOf(paise)
            .divide(PAISE_PER_RUPEE_DECIMAL, 0, mode)
            .multiply(PAISE_PER_RUPEE_DECIMAL)
            .longValueExact());
  }

  /** The larger of this amount and {@code other}, used to apply a minimum fare. */
  public Money max(Money other) {
    Objects.requireNonNull(other, "other");
    return paise >= other.paise ? this : other;
  }

  /** The smaller of this amount and {@code other}. */
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

  public boolean isNegative() {
    return paise < 0;
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
   * This amount formatted as rupees and paise, for example {@code -1234} becomes {@code -12.34}.
   *
   * <p>For display and logs only. Never parse this back into a {@code Money}.
   */
  public String toRupeeString() {
    // absExact rather than abs: Math.abs(Long.MIN_VALUE) is still negative and would
    // silently print a nonsensical amount.
    long absolute = Math.absExact(paise);
    return "%s%d.%02d"
        .formatted(paise < 0 ? "-" : "", absolute / PAISE_PER_RUPEE, absolute % PAISE_PER_RUPEE);
  }
}
