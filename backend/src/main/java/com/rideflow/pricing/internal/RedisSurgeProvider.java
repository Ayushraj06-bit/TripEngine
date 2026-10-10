package com.rideflow.pricing.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The surge multiplier a quote is priced at (ADR 0014), first that applies:
 *
 * <ol>
 *   <li>the kill switch is on: 1.0, everywhere
 *   <li>an admin override for the cell, until it expires
 *   <li>the value the surge job computed
 *   <li>1.0 - including when the job has stopped and its keys have lapsed
 * </ol>
 *
 * <p>Kept for {@code cache-seconds} per cell, so the three vehicle types of one quote, and every
 * rider in the same cell, cost one Redis read between them. An admin change reaches other instances
 * within that time. The cache keeps time by the injected clock, like everything else.
 *
 * <p>Fails safe through {@code QuoteService}: if Redis cannot answer, the quote is priced at 1.0.
 */
@Component
class RedisSurgeProvider implements SurgeProvider {

  /** Cells cached at once: far more than one city surges in, small enough to never matter. */
  private static final int MAX_CELLS = 50_000;

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final SurgeCalculator calculator;
  private final int precision;
  private final Cache<String, BigDecimal> byCell;

  RedisSurgeProvider(StringRedisTemplate redis, Clock clock, RideFlowProperties properties) {
    this.redis = redis;
    this.clock = clock;
    this.calculator = new SurgeCalculator(properties.surge());
    this.precision = properties.surge().geohashPrecision();
    this.byCell =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.surge().cacheSeconds()))
            .maximumSize(MAX_CELLS)
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
            .build();
  }

  @Override
  public BigDecimal multiplierAt(GeoPoint pickup, VehicleType type) {
    return byCell.get(Geohash.encode(pickup, precision), this::lookUp);
  }

  /** Drops one cell's cached value, so a change made here applies here at once. */
  void forget(String cell) {
    byCell.invalidate(cell);
  }

  void forgetAll() {
    byCell.invalidateAll();
  }

  private BigDecimal lookUp(String cell) {
    List<String> values =
        redis
            .opsForValue()
            .multiGet(
                List.of(
                    SurgeKeys.KILL_SWITCH, SurgeKeys.override(cell), SurgeKeys.multiplier(cell)));
    if (values.get(0) != null) {
      return SurgeCalculator.NONE;
    }
    Optional<SurgeOverride> override =
        SurgeOverride.decode(cell, values.get(1))
            .filter(found -> found.expiresAt().isAfter(clock.instant()));
    if (override.isPresent()) {
      return calculator.forPricing(override.get().multiplier());
    }
    return values.get(2) == null
        ? SurgeCalculator.NONE
        : calculator.forPricing(new BigDecimal(values.get(2)));
  }

  /** An admin override as stored: the multiplier and when it ends, checked against our clock. */
  record SurgeOverride(String cell, BigDecimal multiplier, Instant expiresAt) {

    String encode() {
      return multiplier.toPlainString() + "|" + expiresAt.toEpochMilli();
    }

    static Optional<SurgeOverride> decode(String cell, String value) {
      if (value == null) {
        return Optional.empty();
      }
      String[] parts = value.split("\\|");
      return Optional.of(
          new SurgeOverride(
              cell, new BigDecimal(parts[0]), Instant.ofEpochMilli(Long.parseLong(parts[1]))));
    }
  }
}
