package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @Nested
  class Construction {

    @Test
    void convertsRupeesToPaise() {
      assertThat(Money.ofRupees(150).paise()).isEqualTo(15_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -100, Long.MIN_VALUE})
    void rejectsNegativeAmounts(long paise) {
      // Direction lives on the ledger entry, not on the sign: a negative Money is a bug.
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Money.ofPaise(paise))
          .withMessageContaining("negative");
    }

    @Test
    void rejectsRupeeAmountsThatOverflow() {
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> Money.ofRupees(Long.MAX_VALUE));
    }

    @Test
    void allowsZero() {
      assertThat(Money.ofPaise(0)).isEqualTo(Money.ZERO);
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
    void subtractsExactlyToZero() {
      assertThat(Money.ofPaise(500).minus(Money.ofPaise(500))).isEqualTo(Money.ZERO);
    }

    @Test
    void refusesToSubtractIntoANegativeAmount() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Money.ofPaise(500).minus(Money.ofPaise(501)))
          .withMessageContaining("negative");
    }

    @Test
    void failsLoudlyOnOverflowRatherThanWrappingAround() {
      Money huge = Money.ofPaise(Long.MAX_VALUE);
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> huge.plus(Money.ofPaise(1)));
    }

    @Test
    void picksTheLargerAmountForAMinimumFare() {
      assertThat(Money.ofPaise(2_500).max(Money.ofPaise(4_000))).isEqualTo(Money.ofPaise(4_000));
    }

    @Test
    void picksTheSmallerAmountToCapACharge() {
      assertThat(Money.ofPaise(2_500).min(Money.ofPaise(4_000))).isEqualTo(Money.ofPaise(2_500));
    }
  }

  @Nested
  @DisplayName("times, with HALF_UP rounding per PROJECT_SPEC section 7")
  class Multiplication {

    @ParameterizedTest
    @CsvSource({
      "10000, 1.0, 10000",
      "10000, 1.25, 12500",
      "10000, 2.5, 25000",
      "13333, 1.0, 13333",
      "4001, 1.25, 5001", // 5001.25 rounds down
    })
    void multipliesBySurgeMultiplier(long paise, String multiplier, long expected) {
      assertThat(Money.ofPaise(paise).times(new BigDecimal(multiplier)))
          .isEqualTo(Money.ofPaise(expected));
    }

    @ParameterizedTest
    @CsvSource({
      // Each of these lands on exactly .5 of a paise, the tie HALF_UP must round away
      // from zero rather than to even.
      "1, 1.5, 2", // 1.5    -> 2
      "3, 1.5, 5", // 4.5    -> 5
      "5, 1.5, 8", // 7.5    -> 8
      "2, 1.25, 3", // 2.5   -> 3
      "6, 1.25, 8", // 7.5   -> 8
    })
    void roundsAnExactHalfPaiseUp(long paise, String multiplier, long expected) {
      assertThat(Money.ofPaise(paise).times(new BigDecimal(multiplier)))
          .isEqualTo(Money.ofPaise(expected));
    }

    @Test
    void multiplyingByOneIsIdentity() {
      assertThat(Money.ofPaise(13_337).times(BigDecimal.ONE)).isEqualTo(Money.ofPaise(13_337));
    }

    @Test
    void rejectsANegativeMultiplier() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Money.ofPaise(100).times(new BigDecimal("-1.0")));
    }
  }

  @Nested
  @DisplayName("rupee rounding, HALF_UP per PROJECT_SPEC section 7")
  class RupeeRounding {

    @ParameterizedTest
    @CsvSource({
      "2550, 2600", // exactly half a rupee rounds up
      "2549, 2500",
      "2450, 2500", // exactly half a rupee rounds up
      "2449, 2400",
      "3000, 3000", // already whole
      "0, 0",
    })
    void roundsToTheNearestRupee(long paise, long expected) {
      assertThat(Money.ofPaise(paise).roundedToRupee()).isEqualTo(Money.ofPaise(expected));
    }
  }

  @Nested
  @DisplayName("commission split, HALF_EVEN per PROJECT_SPEC sections 7 and 9")
  class CommissionSplit {

    private static final int COMMISSION_PERCENT = 20;

    @Test
    void splitsTheSpecExampleWithoutLosingAPaise() {
      Money fare = Money.ofPaise(12_345);

      Money commission = fare.percentage(COMMISSION_PERCENT);
      Money driverEarnings = fare.minus(commission);

      assertThat(commission).isEqualTo(Money.ofPaise(2_469));
      assertThat(driverEarnings).isEqualTo(Money.ofPaise(9_876));
      assertThat(commission.plus(driverEarnings)).isEqualTo(fare);
    }

    @Test
    void givesTheRemainderToTheDriver() {
      Money fare = Money.ofPaise(13_333);

      Money commission = fare.percentage(COMMISSION_PERCENT);
      Money driverEarnings = fare.minus(commission);

      assertThat(commission).isEqualTo(Money.ofPaise(2_667));
      assertThat(driverEarnings).isEqualTo(Money.ofPaise(10_666));
      assertThat(commission.plus(driverEarnings)).isEqualTo(fare);
    }

    @Test
    void breaksTiesTowardsTheEvenAmount() {
      // 50% of an odd number of paise is always an exact half. HALF_EVEN picks the even
      // neighbour, so a long run of splits does not drift upwards the way HALF_UP would.
      assertThat(Money.ofPaise(2_501).percentage(50)).isEqualTo(Money.ofPaise(1_250));
      assertThat(Money.ofPaise(2_503).percentage(50)).isEqualTo(Money.ofPaise(1_252));
    }

    @Test
    void balancesForEveryFareAcrossAWideRange() {
      // The property that actually matters: PROJECT_SPEC section 2 requires the ledger
      // to balance to zero, for every fare, not just the ones someone thought to test.
      for (long paise = 0; paise <= 20_000; paise++) {
        Money fare = Money.ofPaise(paise);
        Money commission = fare.percentage(COMMISSION_PERCENT);
        Money driverEarnings = fare.minus(commission);

        assertThat(commission.plus(driverEarnings))
            .withFailMessage("split of %d paise did not balance", paise)
            .isEqualTo(fare);
      }
    }

    @Test
    void takesNothingAtZeroPercentAndEverythingAtAHundred() {
      Money fare = Money.ofPaise(12_345);
      assertThat(fare.percentage(0)).isEqualTo(Money.ZERO);
      assertThat(fare.percentage(100)).isEqualTo(fare);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void rejectsAPercentageOutsideRange(int percent) {
      assertThatIllegalArgumentException().isThrownBy(() -> Money.ofPaise(100).percentage(percent));
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

    @ParameterizedTest
    @CsvSource({"1234, 12.34", "5, 0.05", "0, 0.00", "100, 1.00", "99, 0.99"})
    void formatsRupeesAndPaise(long paise, String expected) {
      assertThat(Money.ofPaise(paise).toRupeeString()).isEqualTo(expected);
    }
  }
}
