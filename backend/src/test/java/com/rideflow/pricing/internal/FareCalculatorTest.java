package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rideflow.pricing.FareBreakdown;
import com.rideflow.shared.Money;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FareCalculatorTest {

  /** The PROJECT_SPEC section 9 cards, exactly as V6 seeds them. */
  private static final RateCard BIKE = card(VehicleType.BIKE, 2000, 700, 100, 3000);

  private static final RateCard AUTO = card(VehicleType.AUTO, 3000, 1300, 150, 4000);
  private static final RateCard CAR = card(VehicleType.CAR, 5000, 1600, 200, 8000);
  private static final RateCard[] CARDS = {BIKE, AUTO, CAR};

  private static RateCard card(
      VehicleType type, long base, long perKm, long perMinute, long minimumFare) {
    return new RateCard(
        UUID.randomUUID(),
        type,
        1,
        Money.ofPaise(base),
        Money.ofPaise(perKm),
        Money.ofPaise(perMinute),
        Money.ofPaise(minimumFare),
        Money.ofPaise(100),
        Money.ofPaise(3000),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static RateCard cardFor(VehicleType type) {
    return switch (type) {
      case BIKE -> BIKE;
      case AUTO -> AUTO;
      case CAR -> CAR;
    };
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # case                             | type | m     | s    | surge | base | distance | time  | top-up | surge  | rounding | total
          below the minimum fare             | CAR  | 1000  | 120  | 1.0   | 5000 | 1600     | 400   | 1000   | 0      | 0        | 8000
          surge 1.0                          | CAR  | 10000 | 1800 | 1.0   | 5000 | 16000    | 6000  | 0      | 0      | 0        | 27000
          surge 1.3                          | CAR  | 10000 | 1800 | 1.3   | 5000 | 16000    | 6000  | 0      | 8100   | 0        | 35100
          surge 2.5                          | CAR  | 10000 | 1800 | 2.5   | 5000 | 16000    | 6000  | 0      | 40500  | 0        | 67500
          minimum fare applies before surge  | CAR  | 1000  | 120  | 2.5   | 5000 | 1600     | 400   | 1000   | 12000  | 0        | 20000
          exactly 50 paise rounds up         | BIKE | 5500  | 0    | 1.0   | 2000 | 3850     | 0     | 0      | 0      | 50       | 5900
          49 paise rounds down               | BIKE | 5070  | 0    | 1.0   | 2000 | 3549     | 0     | 0      | 0      | -49      | 5500
          zero minutes costs no time         | AUTO | 5000  | 0    | 1.0   | 3000 | 6500     | 0     | 0      | 0      | 0        | 9500
          nothing at all is the minimum      | AUTO | 0     | 0    | 1.0   | 3000 | 0        | 0     | 1000   | 0      | 0        | 4000
          a part-minute rounds to the paisa  | BIKE | 10000 | 61   | 1.0   | 2000 | 7000     | 102   | 0      | 0      | -2       | 9100
          surge landing on a part-paisa      | AUTO | 7310  | 900  | 1.3   | 3000 | 9503     | 2250  | 0      | 4426   | 21       | 19200
          the longest trip at the surge cap  | CAR  | 60000 | 9819 | 2.5   | 5000 | 96000    | 32730 | 0      | 200595 | -25      | 334300
          """)
  void pricesATrip(
      String description,
      VehicleType type,
      long meters,
      long seconds,
      BigDecimal surge,
      long base,
      long distance,
      long time,
      long topUp,
      long surgeCharge,
      long rounding,
      long total) {

    FareBreakdown fare = FareCalculator.calculate(cardFor(type), meters, seconds, surge);

    assertThat(fare)
        .isEqualTo(
            new FareBreakdown(
                Money.ofPaise(base),
                Money.ofPaise(distance),
                Money.ofPaise(time),
                Money.ofPaise(topUp),
                Money.ofPaise(surgeCharge),
                rounding,
                Money.ofPaise(total)));
  }

  @Nested
  class Rejects {

    @Test
    void aNegativeDistance() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> FareCalculator.calculate(CAR, -1, 60, BigDecimal.ONE));
    }

    @Test
    void aNegativeDuration() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> FareCalculator.calculate(CAR, 1000, -1, BigDecimal.ONE));
    }

    @Test
    void aSurgeBelowOne() {
      // Surge never makes a trip cheaper; a discount is a different feature.
      assertThatIllegalArgumentException()
          .isThrownBy(() -> FareCalculator.calculate(CAR, 1000, 60, new BigDecimal("0.99")));
    }
  }

  /**
   * Properties over many random trips, the way jqwik would check them (ADR 0003). The seed is
   * fixed, so a failure reproduces exactly, and every assertion names the inputs that broke it.
   */
  @Nested
  class Properties {

    private static final long SEED = 20261001L;
    private static final int TRIALS = 10_000;

    @Test
    void neverChargesBelowTheMinimumFareOrANegativeAmount() {
      Random random = new Random(SEED);
      for (int trial = 0; trial < TRIALS; trial++) {
        RateCard card = CARDS[random.nextInt(CARDS.length)];
        long meters = random.nextLong(0, 60_001);
        long seconds = random.nextLong(0, 4 * 3600 + 1);
        BigDecimal surge = randomSurge(random);

        FareBreakdown fare = FareCalculator.calculate(card, meters, seconds, surge);

        String inputs =
            "%s, %d m, %d s, surge %s".formatted(card.vehicleType(), meters, seconds, surge);
        assertThat(fare.total().paise()).as(inputs).isNotNegative();
        assertThat(fare.total()).as(inputs).isGreaterThanOrEqualTo(card.minimumFare());
        assertThat(fare.total().paise() % 100).as(inputs + ": whole rupees").isZero();
        assertThat(fare.roundingPaise()).as(inputs).isBetween(-49L, 50L);
      }
    }

    @Test
    void neverGetsCheaperAsTheTripGetsLonger() {
      Random random = new Random(SEED);
      for (int trial = 0; trial < TRIALS; trial++) {
        RateCard card = CARDS[random.nextInt(CARDS.length)];
        long meters = random.nextLong(0, 55_001);
        long longer = meters + random.nextLong(1, 5_001);
        long seconds = random.nextLong(0, 4 * 3600 + 1);
        BigDecimal surge = randomSurge(random);

        Money shortFare = FareCalculator.calculate(card, meters, seconds, surge).total();
        Money longFare = FareCalculator.calculate(card, longer, seconds, surge).total();

        assertThat(longFare)
            .as(
                "%s, %d m then %d m, %d s, surge %s",
                card.vehicleType(), meters, longer, seconds, surge)
            .isGreaterThanOrEqualTo(shortFare);
      }
    }

    /** 1.00 to 2.50, the range the surge engine can produce (PROJECT_SPEC section 9). */
    private static BigDecimal randomSurge(Random random) {
      return BigDecimal.valueOf(100 + random.nextInt(151), 2);
    }
  }
}
