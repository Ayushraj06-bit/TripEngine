package com.rideflow.pricing.internal;

import com.rideflow.pricing.SurgeAdministration;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.RideFlowProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Admins' view of surge and their two levers: an override per cell, and the kill switch.
 *
 * <p>Every change is logged with the admin who made it. Changes reach this instance at once and
 * every other within {@code cache-seconds}.
 */
@Service
class SurgeAdministrator implements SurgeAdministration {

  private static final Logger log = LoggerFactory.getLogger(SurgeAdministrator.class);

  private final StringRedisTemplate redis;
  private final SurgeJob job;
  private final RedisSurgeProvider provider;
  private final SurgeCalculator calculator;
  private final Clock clock;
  private final RideFlowProperties.Surge rules;

  SurgeAdministrator(
      StringRedisTemplate redis,
      SurgeJob job,
      RedisSurgeProvider provider,
      Clock clock,
      RideFlowProperties properties) {
    this.redis = redis;
    this.job = job;
    this.provider = provider;
    this.calculator = new SurgeCalculator(properties.surge());
    this.clock = clock;
    this.rules = properties.surge();
  }

  @Override
  public Heatmap heatmap() {
    List<Cell> cells = new ArrayList<>();
    job.snapshot()
        .forEach(
            (cell, priced) -> {
              Geohash.Bounds bounds = Geohash.bounds(cell);
              cells.add(
                  new Cell(
                      cell,
                      bounds.center().latitude(),
                      bounds.center().longitude(),
                      bounds.minLat(),
                      bounds.maxLat(),
                      bounds.minLng(),
                      bounds.maxLng(),
                      calculator.forPricing(priced.multiplier()),
                      priced.demand(),
                      priced.supply()));
            });
    cells.sort(Comparator.comparing(Cell::multiplier).reversed().thenComparing(Cell::cell));
    return new Heatmap(
        Boolean.TRUE.equals(redis.hasKey(SurgeKeys.KILL_SWITCH)), cells, overrides());
  }

  @Override
  public CellOverride setOverride(
      String cell, BigDecimal multiplier, Instant expiresAt, UUID adminId) {
    requireCell(cell);
    if (multiplier.compareTo(SurgeCalculator.NONE) < 0
        || multiplier.compareTo(calculator.cap()) > 0) {
      throw BusinessException.businessRule(
          "OVERRIDE_OUT_OF_RANGE",
          "An override must be between 1.0 and %s".formatted(calculator.cap().toPlainString()));
    }
    Instant now = clock.instant();
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(now.plus(Duration.ofHours(rules.overrideMaxHours())))) {
      throw BusinessException.businessRule(
          "INVALID_OVERRIDE_EXPIRY",
          "An override must end in the future, within %d hours"
              .formatted(rules.overrideMaxHours()));
    }

    RedisSurgeProvider.SurgeOverride override =
        new RedisSurgeProvider.SurgeOverride(cell, calculator.forPricing(multiplier), expiresAt);
    redis
        .opsForValue()
        .set(SurgeKeys.override(cell), override.encode(), Duration.between(now, expiresAt));
    redis.opsForZSet().add(SurgeKeys.OVERRIDES, cell, expiresAt.toEpochMilli());
    provider.forget(cell);
    log.info(
        "Admin {} set surge in {} to {} until {}", adminId, cell, override.multiplier(), expiresAt);
    return new CellOverride(cell, override.multiplier(), expiresAt);
  }

  @Override
  public void removeOverride(String cell, UUID adminId) {
    requireCell(cell);
    redis.delete(SurgeKeys.override(cell));
    redis.opsForZSet().remove(SurgeKeys.OVERRIDES, cell);
    provider.forget(cell);
    log.info("Admin {} removed the surge override in {}", adminId, cell);
  }

  @Override
  public KillSwitch setKillSwitch(boolean on, UUID adminId) {
    if (on) {
      redis.opsForValue().set(SurgeKeys.KILL_SWITCH, "1");
    } else {
      redis.delete(SurgeKeys.KILL_SWITCH);
    }
    provider.forgetAll();
    log.warn(
        "Admin {} turned the surge kill switch {}", adminId, on ? "ON: every fare at 1.0" : "off");
    return new KillSwitch(on);
  }

  /** Overrides still in force; ones whose time has passed are dropped from the index on the way. */
  private List<CellOverride> overrides() {
    long now = clock.millis();
    redis.opsForZSet().removeRangeByScore(SurgeKeys.OVERRIDES, Double.NEGATIVE_INFINITY, now);
    Set<String> cells = redis.opsForZSet().range(SurgeKeys.OVERRIDES, 0, -1);
    if (cells == null || cells.isEmpty()) {
      return List.of();
    }
    List<String> ordered = new ArrayList<>(cells);
    List<String> values =
        redis.opsForValue().multiGet(ordered.stream().map(SurgeKeys::override).toList());
    List<CellOverride> overrides = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      RedisSurgeProvider.SurgeOverride.decode(ordered.get(i), values.get(i))
          .filter(found -> found.expiresAt().toEpochMilli() > now)
          .ifPresent(
              found ->
                  overrides.add(
                      new CellOverride(found.cell(), found.multiplier(), found.expiresAt())));
    }
    return overrides;
  }

  private void requireCell(String cell) {
    if (!Geohash.isValid(cell, rules.geohashPrecision())) {
      throw new BusinessException(
          "INVALID_CELL",
          HttpStatus.BAD_REQUEST,
          "A cell is a geohash of %d characters".formatted(rules.geohashPrecision()));
    }
  }
}
