package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KeyedRateLimiterTest {

  private final MutableClock clock = MutableClock.fixedAtEpochDay();

  /** Three a minute refills one token every 20 seconds. */
  private final KeyedRateLimiter limiter =
      new KeyedRateLimiter(3, Duration.ofMinutes(1), "widgets", clock);

  private void drain(String key) {
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryConsume(key)).isEmpty();
    }
  }

  @Test
  void allowsTheCapacityThenSaysHowLongToWait() {
    drain("a");

    assertThat(limiter.tryConsume("a")).hasValue(20);
  }

  @Test
  void refillsOnTheInjectedClock() {
    drain("a");

    clock.advance(Duration.ofSeconds(19));
    assertThat(limiter.tryConsume("a")).hasValue(1);

    clock.advance(Duration.ofSeconds(1));
    assertThat(limiter.tryConsume("a")).isEmpty();
  }

  @Test
  void roundsAPartSecondUpRatherThanToZero() {
    drain("a");
    clock.advance(Duration.ofMillis(19_500));

    assertThat(limiter.tryConsume("a")).hasValue(1);
  }

  @Test
  void keepsEveryKeySeparate() {
    drain("a");

    assertThat(limiter.tryConsume("b")).isEmpty();
  }

  @Test
  void refusesWithA429CarryingTheWait() {
    drain("a");

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> limiter.consume("a"))
        .satisfies(
            e -> {
              assertThat(e.code()).isEqualTo("RATE_LIMITED");
              assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(e.getMessage()).contains("widgets", "20 seconds");
              assertThat(e.properties()).containsEntry(KeyedRateLimiter.RETRY_AFTER_SECONDS, 20L);
            });
  }

  @Test
  void resetForgetsEveryBucket() {
    drain("a");

    limiter.reset();

    assertThat(limiter.tryConsume("a")).isEmpty();
  }

  @Test
  void rejectsANonPositiveCapacity() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new KeyedRateLimiter(0, Duration.ofMinutes(1), "widgets", clock));
  }
}
