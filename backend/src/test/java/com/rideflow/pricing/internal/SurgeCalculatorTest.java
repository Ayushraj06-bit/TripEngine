package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.shared.RideFlowProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The surge rules with the real {@code application.yml} step table: up to a ratio of 1.0, 1.0; to
 * 1.5, 1.2; to 2.0, 1.5; to 3.0, 2.0; above, the 2.5 cap. Steps of 0.25 a tick.
 */
class SurgeCalculatorTest {

  private final SurgeCalculator calculator = new SurgeCalculator(rules());

  private static RideFlowProperties.Surge rules() {
    try {
      var sources =
          new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
      return new Binder(ConfigurationPropertySources.from(sources))
          .bind("rideflow", RideFlowProperties.class)
          .get()
          .surge();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Nested
  class Target {

    @ParameterizedTest(name = "demand {0}, supply {1} -> {2}")
    @CsvSource({
      // No demand: nothing to price, whatever the supply.
      "0, 0, 1.0",
      "0, 50, 1.0",
      // No drivers: counted as one, so the ratio is the demand.
      "1, 0, 1.0",
      "2, 0, 1.5",
      // Exactly on each boundary: the step it closes.
      "10, 10, 1.0",
      "15, 10, 1.2",
      "20, 10, 1.5",
      "30, 10, 2.0",
      // Just past each boundary: the next step.
      "101, 100, 1.2",
      "151, 100, 1.5",
      "201, 100, 2.0",
      "301, 100, 2.5",
      // However high: the cap.
      "1000000, 1, 2.5",
    })
    void looksTheRatioUpInTheStepTable(long demand, long supply, String target) {
      assertThat(calculator.target(demand, supply)).isEqualByComparingTo(target);
    }
  }

  @Nested
  class Smoothing {

    private List<String> ticks(String from, String to, int count) {
      List<String> seen = new ArrayList<>();
      BigDecimal value = new BigDecimal(from);
      for (int i = 0; i < count; i++) {
        value = calculator.smooth(value, new BigDecimal(to));
        seen.add(value.toPlainString());
      }
      return seen;
    }

    @Test
    void climbsOneStepATickToTheTargetAndStays() {
      assertThat(ticks("1.0", "2.5", 7))
          .containsExactly("1.25", "1.5", "1.75", "2", "2.25", "2.5", "2.5");
    }

    @Test
    void comesDownTheSameWay() {
      assertThat(ticks("2.5", "1.0", 7))
          .containsExactly("2.25", "2", "1.75", "1.5", "1.25", "1", "1");
    }

    @Test
    void closesAGapSmallerThanAStepInOne() {
      assertThat(calculator.smooth(new BigDecimal("1.2"), new BigDecimal("1.3")))
          .isEqualByComparingTo("1.3");
    }

    @Test
    void neverLeavesOneToTheCap() {
      assertThat(calculator.smooth(new BigDecimal("0.5"), BigDecimal.ONE))
          .isEqualByComparingTo("1");
      assertThat(calculator.smooth(new BigDecimal("2.4"), new BigDecimal("9")))
          .isEqualByComparingTo("2.5");
    }
  }

  /** Smoothed values stay exact; a fare is priced at the value rounded to 0.1, half up. */
  @ParameterizedTest(name = "{0} is priced at {1}")
  @CsvSource({
    "1, 1",
    "1.25, 1.3",
    "1.5, 1.5",
    "1.75, 1.8",
    "2.25, 2.3",
    "2.5, 2.5",
    "0.8, 1",
    "3.1, 2.5"
  })
  void pricesAtTheValueRoundedToATenth(String smoothed, String priced) {
    assertThat(calculator.forPricing(new BigDecimal(smoothed))).isEqualByComparingTo(priced);
  }
}
