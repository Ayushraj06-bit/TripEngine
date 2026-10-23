package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

/** The buckets are in a real Redis, as in production: every instance shares them. */
@Tag("integration")
class KeyedRateLimiterTest {

  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);
  private static final RedisContainer REDIS = new RedisContainer("redis:7.4.11-alpine");

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> inspector;
  private static RateLimitBuckets buckets;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
    client = RedisClient.create(REDIS.getRedisURI());
    inspector = client.connect();
    buckets = new RateLimitBuckets(() -> client, CLOCK, Duration.ofSeconds(1));
  }

  @AfterAll
  static void stopRedis() {
    buckets.close();
    inspector.close();
    client.shutdown();
    REDIS.stop();
  }

  /** Three a minute refills one token every 20 seconds. */
  private final KeyedRateLimiter limiter = widgets();

  private static KeyedRateLimiter widgets() {
    return new KeyedRateLimiter("widgets", 3, Duration.ofMinutes(1), "widgets", buckets);
  }

  @BeforeEach
  void startEmpty() {
    CLOCK.set(START);
    limiter.reset();
  }

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
    CLOCK.advance(Duration.ofSeconds(19));
    assertThat(limiter.tryConsume("a")).hasValue(1);

    CLOCK.advance(Duration.ofSeconds(1));
    assertThat(limiter.tryConsume("a")).isEmpty();
  }

  @Test
  void roundsAPartSecondUpRatherThanToZero() {
    drain("a");
    CLOCK.advance(Duration.ofMillis(19_500));

    assertThat(limiter.tryConsume("a")).hasValue(1);
  }

  @Test
  void keepsEveryKeySeparate() {
    drain("a");

    assertThat(limiter.tryConsume("b")).isEmpty();
  }

  /** The point of keeping them in Redis: a second instance does not get a second allowance. */
  @Test
  void sharesOneBudgetBetweenInstances() {
    KeyedRateLimiter otherInstance = widgets();
    drain("a");

    assertThat(otherInstance.tryConsume("a")).hasValue(20);
  }

  @Test
  void keepsNoReadableKeyInRedisAndLetsEveryBucketExpire() {
    limiter.tryConsume("+919800000001");

    List<String> keys = inspector.sync().keys("rate:widgets:*");
    assertThat(keys).singleElement().asString().doesNotContain("9800000001");
    assertThat(inspector.sync().pttl(keys.getFirst())).isPositive();
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

  /** Without Redis no request can be counted: a 503, as for every other request needing it. */
  @Test
  void reportsRedisAwayAsUnavailable() {
    RedisClient nowhere = RedisClient.create("redis://127.0.0.1:1");
    try (RateLimitBuckets unreachable =
        new RateLimitBuckets(() -> nowhere, CLOCK, Duration.ofSeconds(1))) {
      KeyedRateLimiter cut =
          new KeyedRateLimiter("widgets", 3, Duration.ofMinutes(1), "widgets", unreachable);

      assertThatExceptionOfType(DataAccessResourceFailureException.class)
          .isThrownBy(() -> cut.tryConsume("a"));
    } finally {
      nowhere.shutdown();
    }
  }

  @Test
  void rejectsANonPositiveCapacity() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new KeyedRateLimiter("widgets", 0, Duration.ofMinutes(1), "widgets", buckets));
  }
}
