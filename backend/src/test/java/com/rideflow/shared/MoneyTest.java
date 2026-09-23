package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Nested
  class Construction {

    @Test
    void convertsRupeesToPaise() {
      assertThat(Money.ofRupees(150).paise()).isEqualTo(15_000);
    }

    @Test
    void rejectsRupeeAmountsThatOverflow() {
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> Money.ofRupees(Long.MAX_VALUE));
    }

    @Test
    void allowsNegativeAmountsForLedgerCorrections() {
      assertThat(Money.ofPaise(-500).isNegative()).isTrue();
    }
  }

  @Nested
  class Arithmetic {

    @Test
    void addsAndSubtracts() {
      Money fare = Money.ofPaise(12_500);
      assertThat(fare.plus(Money.ofPaise(500))).isEqualTo(Money.ofPaise(13_000));
      assertThat(fare.minus(Money.ofPaise(500))).isEqualTo(Money.ofPaise(12_000));
    }

    @Test
    void failsLoudlyOnOverflowRatherThanWrappingAround() {
      Money huge = Money.ofPaise(Long.MAX_VALUE);
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> huge.plus(Money.ofPaise(1)));
    }

    @Test
    void appliesSurgeMultiplier() {
      Money base = Money.ofPaise(10_000);
      assertThat(base.multipliedBy(new BigDecimal("1.5"), RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(15_000));
    }

    @Test
    void roundsAFractionalMultiplierToWholePaise() {
      // 10001 * 1.25 = 12501.25
      Money base = Money.ofPaise(10_001);
      assertThat(base.multipliedBy(new BigDecimal("1.25"), RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(12_501));
    }

    @Test
    void picksTheLargerAmountForAMinimumFare() {
      Money computed = Money.ofPaise(2_500);
      Money minimumFare = Money.ofPaise(4_000);
      assertThat(computed.max(minimumFare)).isEqualTo(minimumFare);
    }
  }

  @Nested
  @DisplayName("rounding, per PROJECT_SPEC section 7")
  class Rounding {

    @Test
    void roundsFaresHalfUpToTheNearestRupee() {
      assertThat(Money.ofPaise(2_550).roundedToRupee(RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(2_600));
      assertThat(Money.ofPaise(2_549).roundedToRupee(RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(2_500));
    }

    @Test
    void roundsAnExactHalfRupeeAwayFromZeroWhenHalfUp() {
      assertThat(Money.ofPaise(2_450).roundedToRupee(RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(2_500));
      assertThat(Money.ofPaise(-2_450).roundedToRupee(RoundingMode.HALF_UP))
          .isEqualTo(Money.ofPaise(-2_500));
    }

    @Test
    void leavesWholeRupeeAmountsUntouched() {
      assertThat(Money.ofRupees(30).roundedToRupee(RoundingMode.HALF_UP))
          .isEqualTo(Money.ofRupees(30));
    }

    @Test
    void breaksTiesTowardsTheEvenAmountWhenHalfEven() {
      // 50% of 2501 is 1250.5 and of 2503 is 1251.5: HALF_EVEN picks the even neighbour,
      // so a long run of splits does not drift upwards the way HALF_UP would.
      assertThat(Money.ofPaise(2_501).percentage(50, RoundingMode.HALF_EVEN))
          .isEqualTo(Money.ofPaise(1_250));
      assertThat(Money.ofPaise(2_503).percentage(50, RoundingMode.HALF_EVEN))
          .isEqualTo(Money.ofPaise(1_252));
    }

    @Test
    void rejectsANegativePercentage() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Money.ofPaise(100).percentage(-1, RoundingMode.HALF_EVEN));
    }
  }

  @Nested
  @DisplayName("commission split, per PROJECT_SPEC sections 7 and 9")
  class CommissionSplit {

    private static final int COMMISSION_PERCENT = 20;

    @Test
    void givesTheRemainderToTheDriverSoNoPaiseIsLost() {
      // An amount that does not divide evenly by the commission percentage.
      Money fare = Money.ofPaise(13_333);

      Money commission = fare.percentage(COMMISSION_PERCENT, RoundingMode.HALF_EVEN);
      Money driverEarnings = fare.minus(commission);

      assertThat(commission).isEqualTo(Money.ofPaise(2_667));
      assertThat(driverEarnings).isEqualTo(Money.ofPaise(10_666));
      // The ledger must balance exactly: this is the property that matters.
      assertThat(commission.plus(driverEarnings)).isEqualTo(fare);
    }

    @Test
    void balancesForEveryFareAcrossAWideRange() {
      for (long paise = 0; paise <= 5_000; paise++) {
        Money fare = Money.ofPaise(paise);
        Money commission = fare.percentage(COMMISSION_PERCENT, RoundingMode.HALF_EVEN);
        Money driverEarnings = fare.minus(commission);

        assertThat(commission.plus(driverEarnings))
            .withFailMessage("split of %s did not balance", fare)
            .isEqualTo(fare);
        assertThat(commission.isNegative())
            .withFailMessage("commission on %s was negative", fare)
            .isFalse();
      }
    }
  }

  @Nested
  class Comparison {

    @Test
    void ordersByAmount() {
      assertThat(Money.ofPaise(100).isGreaterThan(Money.ofPaise(99))).isTrue();
      assertThat(Money.ofPaise(100).isLessThan(Money.ofPaise(101))).isTrue();
      assertThat(Money.ofPaise(100).compareTo(Money.ofPaise(100))).isZero();
    }

    @Test
    void treatsEqualAmountsAsEqualValues() {
      assertThat(Money.ofRupees(1)).isEqualTo(Money.ofPaise(100));
    }
  }

  @Nested
  class Formatting {

    @Test
    void formatsRupeesAndPaise() {
      assertThat(Money.ofPaise(1_234).toRupeeString()).isEqualTo("12.34");
      assertThat(Money.ofPaise(5).toRupeeString()).isEqualTo("0.05");
      assertThat(Money.ZERO.toRupeeString()).isEqualTo("0.00");
    }

    @Test
    void keepsTheSignOnNegativeAmounts() {
      assertThat(Money.ofPaise(-1_234).toRupeeString()).isEqualTo("-12.34");
      assertThat(Money.ofPaise(-5).toRupeeString()).isEqualTo("-0.05");
    }

    @Test
    void refusesToPrintAnAmountItCannotNegate() {
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> Money.ofPaise(Long.MIN_VALUE).toRupeeString());
    }
  }
}
