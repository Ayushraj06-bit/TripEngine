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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final SurgeLevers levers;
  private final SurgeJob job;
  private final SurgeCalculator calculator;
  private final Clock clock;
  private final RideFlowProperties.Surge rules;

  SurgeAdministrator(SurgeLevers levers, SurgeJob job, Clock clock, RideFlowProperties properties) {
    this.levers = levers;
    this.job = job;
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
    SurgeLevers.Levers now = levers.read();
    return new Heatmap(now.off(), cells, inForce(now));
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

    CellOverride override = new CellOverride(cell, calculator.forPricing(multiplier), expiresAt);
    levers.setOverride(cell, override.multiplier(), expiresAt);
    log.info(
        "Admin {} set surge in {} to {} until {}", adminId, cell, override.multiplier(), expiresAt);
    return override;
  }

  @Override
  public void removeOverride(String cell, UUID adminId) {
    requireCell(cell);
    levers.removeOverride(cell);
    log.info("Admin {} removed the surge override in {}", adminId, cell);
  }

  @Override
  public KillSwitch setKillSwitch(boolean on, UUID adminId) {
    levers.setOff(on);
    log.warn(
        "Admin {} turned the surge kill switch {}", adminId, on ? "ON: every fare at 1.0" : "off");
    return new KillSwitch(on);
  }

  private List<CellOverride> inForce(SurgeLevers.Levers levers) {
    Instant now = clock.instant();
    return levers.overrides().values().stream()
        .filter(override -> override.expiresAt().isAfter(now))
        .toList();
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
