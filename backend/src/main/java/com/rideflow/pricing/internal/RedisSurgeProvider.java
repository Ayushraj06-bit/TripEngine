package com.rideflow.pricing.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.rideflow.pricing.SurgeAdministration.CellOverride;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
 * <p>The computed value is kept for {@code cache-seconds} per cell, so the three vehicle types of
 * one quote, and every rider in the same cell, cost one Redis read between them. The levers are
 * kept apart from it, for as long ({@link SurgeLevers}), so an admin change reaches other instances
 * within that time and not twice it.
 *
 * <p>The cache measures age with a monotonic {@link Ticker}, not the wall clock: were the clock
 * stepped back an hour, every cached value would otherwise live an hour more, and a kill switch
 * thrown on another instance would not reach this one for as long. Age is elapsed time, not
 * business time, so the injected {@code Clock} is not the thing to ask; tests supply a ticker of
 * their own.
 *
 * <p>Fails safe through {@code QuoteService}: if Redis or PostgreSQL cannot answer, the quote is
 * priced at 1.0.
 */
@Component
class RedisSurgeProvider implements SurgeProvider {

  /** Cells cached at once: far more than one city surges in, small enough to never matter. */
  private static final int MAX_CELLS = 50_000;

  private final StringRedisTemplate redis;
  private final SurgeLevers levers;
  private final Clock clock;
  private final SurgeCalculator calculator;
  private final int precision;
  private final Cache<String, BigDecimal> byCell;

  RedisSurgeProvider(
      StringRedisTemplate redis,
      SurgeLevers levers,
      Clock clock,
      Ticker surgeCacheTicker,
      RideFlowProperties properties) {
    this.redis = redis;
    this.levers = levers;
    this.clock = clock;
    this.calculator = new SurgeCalculator(properties.surge());
    this.precision = properties.surge().geohashPrecision();
    this.byCell =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.surge().cacheSeconds()))
            .maximumSize(MAX_CELLS)
            .ticker(surgeCacheTicker)
            .build();
  }

  @Override
  public BigDecimal multiplierAt(GeoPoint pickup, VehicleType type) {
    SurgeLevers.Levers now = levers.current();
    if (now.off()) {
      return SurgeCalculator.NONE;
    }
    String cell = Geohash.encode(pickup, precision);
    CellOverride override = now.overrides().get(cell);
    if (override != null && override.expiresAt().isAfter(clock.instant())) {
      return calculator.forPricing(override.multiplier());
    }
    return byCell.get(cell, this::computed);
  }

  /** Forgets every cached value, for tests that change what is underneath. */
  void forgetAll() {
    byCell.invalidateAll();
    levers.forget();
  }

  private BigDecimal computed(String cell) {
    String value = redis.opsForValue().get(SurgeKeys.multiplier(cell));
    return value == null ? SurgeCalculator.NONE : calculator.forPricing(new BigDecimal(value));
  }
}
