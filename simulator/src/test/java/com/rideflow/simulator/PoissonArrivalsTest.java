package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded, so a failure reproduces; the tolerances are a few standard errors wide. */
class PoissonArrivalsTest {

  private static final double PER_MINUTE = 30;

  @Test
  void spacesArrivalsAtTheConfiguredRateOnAverage() {
    Random random = new Random(7);
    int samples = 20_000;
    double sum = 0;
    double sumOfSquares = 0;
    for (int i = 0; i < samples; i++) {
      double seconds = PoissonArrivals.nextGap(PER_MINUTE, random).toNanos() / 1e9;
      sum += seconds;
      sumOfSquares += seconds * seconds;
    }
    double mean = sum / samples;
    double deviation = Math.sqrt(sumOfSquares / samples - mean * mean);

    // 30 a minute: two seconds apart on average, and - exponential - as spread as the mean.
    assertThat(mean).isCloseTo(2.0, within(0.05));
    assertThat(deviation).isCloseTo(2.0, within(0.1));
  }

  @Test
  void countsPerMinuteFollowAPoissonDistribution() {
    Random random = new Random(11);
    int minutes = 2_000;
    int[] counts = new int[minutes];
    Duration clock = Duration.ZERO;
    Duration end = Duration.ofMinutes(minutes);
    while (true) {
      clock = clock.plus(PoissonArrivals.nextGap(PER_MINUTE, random));
      if (clock.compareTo(end) >= 0) {
        break;
      }
      counts[(int) clock.toMinutes()]++;
    }

    double mean = 0;
    for (int count : counts) {
      mean += count;
    }
    mean /= minutes;
    double variance = 0;
    for (int count : counts) {
      variance += (count - mean) * (count - mean);
    }
    variance /= minutes - 1;

    // A Poisson count has its variance equal to its mean: bursts and lulls, not a metronome.
    assertThat(mean).isCloseTo(PER_MINUTE, within(0.5));
    assertThat(variance / mean).isCloseTo(1.0, within(0.1));
  }

  @Test
  void neverWaitsANegativeOrInfiniteTime() {
    Random random = new Random(3);
    for (int i = 0; i < 100_000; i++) {
      Duration gap = PoissonArrivals.nextGap(PER_MINUTE, random);
      assertThat(gap).isPositive();
      assertThat(gap).isLessThan(Duration.ofHours(1));
    }
  }

  @Test
  void refusesARateThatIsNotPositive() {
    assertThatThrownBy(() -> PoissonArrivals.nextGap(0, new Random()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
