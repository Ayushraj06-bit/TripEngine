package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.shared.Money;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommissionSplitTest {

  @Test
  void givesTheRemainderOfAnOddFareToTheDriver() {
    CommissionSplit split = CommissionSplit.of(Money.ofPaise(12_345), 20);

    assertThat(split.commission()).isEqualTo(Money.ofPaise(2_469));
    assertThat(split.driverShare()).isEqualTo(Money.ofPaise(9_876));
  }

  /** 25% lands on exact halves: HALF_EVEN rounds them to the even neighbour, both ways. */
  @ParameterizedTest(name = "{1}% of {0} -> commission {2}")
  @CsvSource({"10, 25, 2", "30, 25, 8", "12347, 20, 2469", "12348, 20, 2470", "1, 20, 0"})
  void roundsTheCommissionHalfEven(long charge, int percent, long commission) {
    CommissionSplit split = CommissionSplit.of(Money.ofPaise(charge), percent);

    assertThat(split.commission().paise()).isEqualTo(commission);
    assertThat(split.driverShare().paise()).isEqualTo(charge - commission);
  }

  /** Any charge, any percentage: the parts come to the whole, and neither is negative. */
  @Test
  void neverLosesOrMakesAPaisa() {
    Random random = new Random(7);
    for (int i = 0; i < 100_000; i++) {
      long charge = random.nextLong(0, 10_000_000);
      int percent = random.nextInt(0, 101);

      CommissionSplit split = CommissionSplit.of(Money.ofPaise(charge), percent);

      assertThat(split.commission().plus(split.driverShare()).paise())
          .as("%d%% of %d", percent, charge)
          .isEqualTo(charge);
    }
  }
}
